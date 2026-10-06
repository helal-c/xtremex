package com.xtremex.tv

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Channel(
  val name:String,
  val category:String,
  val src:String,
  val logo:String?=null,
  val group:String?=null
)

class MainActivity:AppCompatActivity(){
  private lateinit var player:ExoPlayer
  private lateinit var playerView:PlayerView
  private lateinit var infoBox:LinearLayout
  private lateinit var numberText:TextView
  private lateinit var nameText:TextView
  private lateinit var metaText:TextView
  private lateinit var guide:LinearLayout
  private lateinit var list:RecyclerView
  private lateinit var search:EditText
  private lateinit var status:TextView
  private lateinit var numberEntry:TextView

  private val handler=Handler(Looper.getMainLooper())
  private val executor=Executors.newSingleThreadExecutor()
  private val prefs by lazy { getSharedPreferences("xtremex-tv",MODE_PRIVATE) }
  private var channels:List<Channel> = emptyList()
  private var currentIndex=0
  private var numeric=""
  private var firstPlayPending=true
  private lateinit var adapter:ChannelAdapter

  override fun onCreate(savedInstanceState:Bundle?){
    super.onCreate(savedInstanceState)
    window.decorView.systemUiVisibility=
      View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

    buildUi()
    buildPlayer()
    adapter=ChannelAdapter{ tune(it); hideGuide() }
    list.layoutManager=LinearLayoutManager(this)
    list.adapter=adapter
    search.doAfterTextChanged { adapter.submit(channels,it?.toString().orEmpty()) }

    val cached=loadCache()
    if(cached.isNotEmpty()) applyChannels(cached,true) else showStatus("Loading channels…",true)

    refreshPlaylist()
    handler.postDelayed({ checkForUpdate() },4000)
  }

  private fun buildUi(){
    val root=FrameLayout(this).apply{ setBackgroundColor(Color.rgb(5,8,13)) }
    playerView=PlayerView(this).apply{
      layoutParams=FrameLayout.LayoutParams(-1,-1)
      useController=false
      isFocusable=false
      keepScreenOn=true
    }
    root.addView(playerView)

    infoBox=LinearLayout(this).apply{
      orientation=LinearLayout.VERTICAL
      setPadding(dp(20),dp(14),dp(20),dp(14))
      background=panelDrawable()
      visibility=View.GONE
    }
    val infoLp=FrameLayout.LayoutParams(dp(600),-2,Gravity.TOP or Gravity.START).apply{ setMargins(dp(28),dp(28),0,0) }
    root.addView(infoBox,infoLp)
    numberText=makeText(14,Color.rgb(244,196,93),true)
    nameText=makeText(24,Color.WHITE,true)
    metaText=makeText(13,Color.rgb(138,150,168),false)
    infoBox.addView(numberText); infoBox.addView(nameText); infoBox.addView(metaText)

    numberEntry=makeText(28,Color.rgb(244,196,93),true).apply{
      background=panelDrawable()
      setPadding(dp(18),dp(10),dp(18),dp(10))
      visibility=View.GONE
    }
    root.addView(numberEntry,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{setMargins(0,dp(28),dp(28),0)})

    guide=LinearLayout(this).apply{
      orientation=LinearLayout.VERTICAL
      setPadding(dp(18),dp(18),dp(18),dp(18))
      setBackgroundColor(Color.argb(242,11,17,27))
      visibility=View.GONE
    }
    root.addView(guide,FrameLayout.LayoutParams(dp(430),-1,Gravity.END))

    guide.addView(makeText(14,Color.rgb(244,196,93),true).apply{text="CHANNEL GUIDE"})
    search=EditText(this).apply{
      hint="Search channel"
      setTextColor(Color.WHITE)
      setHintTextColor(Color.rgb(110,122,140))
      textSize=15f
      isSingleLine=true
      setPadding(dp(14),0,dp(14),0)
      background=panelDrawable()
    }
    guide.addView(search,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(10)})

    list=RecyclerView(this)
    guide.addView(list,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(12)})

    status=makeText(13,Color.WHITE,true).apply{
      background=panelDrawable()
      setPadding(dp(14),dp(9),dp(14),dp(9))
      visibility=View.GONE
    }
    root.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply{setMargins(dp(22),0,0,dp(22))})
    setContentView(root)
  }

  private fun buildPlayer(){
    player=ExoPlayer.Builder(this).build()
    playerView.player=player
    player.addListener(object:Player.Listener{
      override fun onPlaybackStateChanged(state:Int){
        when(state){
          Player.STATE_BUFFERING->showStatus("Connecting…")
          Player.STATE_READY->if(player.playWhenReady)showStatus("LIVE")
          Player.STATE_ENDED->retry()
        }
      }
      override fun onPlayerError(error:PlaybackException){
        showStatus("Signal lost — retrying")
        handler.postDelayed({retry()},2200)
      }
    })
  }

  private fun refreshPlaylist(){
    executor.execute{
      val result=runCatching{
        val c=URL(CHANNELS_URL).openConnection() as HttpURLConnection
        c.connectTimeout=10000;c.readTimeout=15000;c.instanceFollowRedirects=true
        c.setRequestProperty("User-Agent","XtremeX-TV-Android/1.0")
        try{
          if(c.responseCode !in 200..299) error("Playlist HTTP "+c.responseCode)
          val text=c.inputStream.bufferedReader().use{it.readText()}
          val parsed=parseChannels(text)
          require(parsed.isNotEmpty())
          openFileOutput(CACHE_FILE,MODE_PRIVATE).bufferedWriter().use{it.write(text)}
          parsed
        } finally { c.disconnect() }
      }
      runOnUiThread{
        result.onSuccess{
          val auto=channels.isEmpty()
          applyChannels(it,auto)
          showStatus("Channel list updated")
        }.onFailure{
          if(channels.isEmpty())showStatus("Playlist unavailable",true)
        }
      }
    }
  }

  private fun loadCache():List<Channel> = runCatching{
    openFileInput(CACHE_FILE).bufferedReader().use{parseChannels(it.readText())}
  }.getOrDefault(emptyList())

  private fun parseChannels(text:String):List<Channel>{
    val a=JSONObject(text).getJSONArray("channels")
    val out=ArrayList<Channel>()
    for(i in 0 until a.length()){
      val o=a.getJSONObject(i)
      val name=o.optString("name").trim()
      val src=o.optString("src").trim()
      if(name.isNotBlank()&&src.isNotBlank()) out+=Channel(
        name,
        o.optString("category","Other"),
        src,
        o.optString("logo").takeIf{it.isNotBlank()},
        o.optString("group").takeIf{it.isNotBlank()}
      )
    }
    return out
  }

  private fun applyChannels(newList:List<Channel>,autoplay:Boolean){
    val previous=channels.getOrNull(currentIndex)?.src
    channels=newList
    adapter.submit(channels,search.text?.toString().orEmpty())
    currentIndex=previous?.let{p->channels.indexOfFirst{it.src==p}.takeIf{it>=0}}
      ?:prefs.getString("last_channel_src",null)?.let{p->channels.indexOfFirst{it.src==p}.takeIf{it>=0}}
      ?:0
    if(autoplay&&firstPlayPending){firstPlayPending=false;tune(currentIndex)}
  }

  private fun tune(index:Int){
    if(channels.isEmpty())return
    currentIndex=((index%channels.size)+channels.size)%channels.size
    val ch=channels[currentIndex]
    prefs.edit().putString("last_channel_src",ch.src).apply()
    player.stop();player.clearMediaItems()
    player.setMediaItem(MediaItem.fromUri(ch.src))
    player.prepare();player.playWhenReady=true
    showInfo()
  }

  private fun retry(){ if(channels.isNotEmpty()) tune(currentIndex) }

  private fun showInfo(){
    val ch=channels.getOrNull(currentIndex)?:return
    numberText.text="%03d".format(currentIndex+1)
    nameText.text=ch.name
    metaText.text=ch.category+"  •  "+if(ch.src.startsWith("http:"))"BDIX / HTTP" else "LIVE"
    infoBox.visibility=View.VISIBLE
    handler.removeCallbacks(HIDE_INFO)
    handler.postDelayed(HIDE_INFO,3500)
  }

  private val HIDE_INFO=Runnable{infoBox.visibility=View.GONE}

  private fun showGuide(){
    guide.visibility=View.VISIBLE
    adapter.submit(channels,search.text?.toString().orEmpty())
    adapter.focusGlobal(list,currentIndex)
  }
  private fun hideGuide(){guide.visibility=View.GONE;search.clearFocus()}

  private fun showStatus(msg:String,persistent:Boolean=false){
    status.text=msg;status.visibility=View.VISIBLE
    handler.removeCallbacks(HIDE_STATUS)
    if(!persistent)handler.postDelayed(HIDE_STATUS,1800)
  }
  private val HIDE_STATUS=Runnable{status.visibility=View.GONE}

  private fun numericKey(n:Int){
    numeric+=n.toString()
    numberEntry.text=numeric;numberEntry.visibility=View.VISIBLE
    handler.removeCallbacks(TUNE_NUMBER)
    handler.postDelayed(TUNE_NUMBER,950)
  }
  private val TUNE_NUMBER=Runnable{
    val n=numeric.toIntOrNull()
    numeric="";numberEntry.visibility=View.GONE
    if(n!=null&&n in 1..channels.size)tune(n-1) else showStatus("Channel not found")
  }

  override fun dispatchKeyEvent(e:KeyEvent):Boolean{
    if(e.action!=KeyEvent.ACTION_DOWN)return super.dispatchKeyEvent(e)
    if(guide.visibility==View.VISIBLE){
      if(e.keyCode==KeyEvent.KEYCODE_BACK||e.keyCode==KeyEvent.KEYCODE_MENU){hideGuide();return true}
      return super.dispatchKeyEvent(e)
    }
    when(e.keyCode){
      KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_CHANNEL_UP,KeyEvent.KEYCODE_MEDIA_NEXT->{tune(currentIndex+1);return true}
      KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_CHANNEL_DOWN,KeyEvent.KEYCODE_MEDIA_PREVIOUS->{tune(currentIndex-1);return true}
      KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_MENU->{showGuide();return true}
      KeyEvent.KEYCODE_INFO,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->{showInfo();return true}
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_SPACE->{if(player.isPlaying)player.pause() else player.play();showInfo();return true}
      in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9->{numericKey(e.keyCode-KeyEvent.KEYCODE_0);return true}
    }
    return super.dispatchKeyEvent(e)
  }

  private fun checkForUpdate(){
    executor.execute{
      val release=runCatching{
        val c=URL("https://api.github.com/repos/helal-c/xtremex/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout=10000;c.readTimeout=12000
        c.setRequestProperty("User-Agent","XtremeX-TV-Android/1.0")
        try{
          if(c.responseCode !in 200..299)error("No release")
          val o=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
          val version=o.optString("tag_name").removePrefix("v")
          val assets=o.getJSONArray("assets")
          var apk=""
          for(i in 0 until assets.length()){
            val a=assets.getJSONObject(i)
            if(a.optString("name").endsWith(".apk",true)){apk=a.optString("browser_download_url");break}
          }
          Triple(version,apk,o.optString("body"))
        }finally{c.disconnect()}
      }.getOrNull()?:return@execute

      if(release.second.isBlank()||!isNewer(release.first,BuildConfig.VERSION_NAME.substringBefore("-")))return@execute
      runOnUiThread{
        AlertDialog.Builder(this)
          .setTitle("XtremeX TV "+release.first+" available")
          .setMessage(release.third.ifBlank{"New update is ready."})
          .setPositiveButton("Update now"){_,_->downloadUpdate(release.second,release.first)}
          .setNegativeButton("Later",null).show()
      }
    }
  }

  private fun isNewer(remote:String,local:String):Boolean{
    val r=remote.split(".").map{it.toIntOrNull()?:0};val l=local.split(".").map{it.toIntOrNull()?:0}
    for(i in 0 until maxOf(r.size,l.size)){val rv=r.getOrElse(i){0};val lv=l.getOrElse(i){0};if(rv!=lv)return rv>lv}
    return false
  }

  private fun downloadUpdate(url:String,version:String){
    if(Build.VERSION.SDK_INT>=26&&!packageManager.canRequestPackageInstalls()){
      startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+packageName)))
      Toast.makeText(this,"Allow installs, then choose Update again.",Toast.LENGTH_LONG).show()
      return
    }
    val dm=getSystemService<DownloadManager>()?:return
    val id=dm.enqueue(
      DownloadManager.Request(Uri.parse(url))
        .setTitle("XtremeX TV "+version)
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,"xtremex-tv-"+version+".apk")
    )
    executor.execute{
      while(true){
        dm.query(DownloadManager.Query().setFilterById(id)).use{cur->
          if(!cur.moveToFirst())return@execute
          when(cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))){
            DownloadManager.STATUS_SUCCESSFUL->{
              val uri=dm.getUriForDownloadedFile(id)?:return@execute
              runOnUiThread{
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
              };return@execute
            }
            DownloadManager.STATUS_FAILED->return@execute
          }
        }
        Thread.sleep(1000)
      }
    }
  }

  private fun makeText(sp:Int,color:Int,bold:Boolean)=TextView(this).apply{
    setTextColor(color);textSize=sp.toFloat();if(bold)setTypeface(typeface,android.graphics.Typeface.BOLD)
  }
  private fun panelDrawable()=GradientDrawable().apply{
    setColor(Color.argb(230,11,17,27));cornerRadius=dp(14).toFloat();setStroke(dp(1),Color.argb(38,255,255,255))
  }
  private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

  override fun onStop(){super.onStop();player.pause()}
  override fun onResume(){super.onResume();if(::player.isInitialized&&channels.isNotEmpty()&&!player.isPlaying)player.play()}
  override fun onDestroy(){handler.removeCallbacksAndMessages(null);player.release();executor.shutdownNow();super.onDestroy()}

  companion object{
    const val CHANNELS_URL="https://xtremextv.vercel.app/channels.json"
    const val CACHE_FILE="channels-cache.json"
  }
}

class ChannelAdapter(private val select:(Int)->Unit):RecyclerView.Adapter<ChannelAdapter.Holder>(){
  private var all:List<Channel> = emptyList()
  private var visible:List<Int> = emptyList()

  fun submit(channels:List<Channel>,query:String){
    all=channels
    visible=channels.indices.filter{
      query.isBlank()||channels[it].name.contains(query,true)||channels[it].category.contains(query,true)
    }
    notifyDataSetChanged()
  }

  fun focusGlobal(rv:RecyclerView,index:Int){
    val p=visible.indexOf(index);if(p<0)return
    rv.scrollToPosition(p);rv.post{rv.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus()}
  }

  override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder{
    val row=LinearLayout(parent.context).apply{
      orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
      setPadding(dp(parent.context,12),0,dp(parent.context,12),0)
      layoutParams=ViewGroup.LayoutParams(-1,dp(parent.context,64))
      isFocusable=true;isClickable=true
    }
    val number=TextView(parent.context).apply{setTextColor(Color.rgb(244,196,93));textSize=14f}
    row.addView(number,LinearLayout.LayoutParams(dp(parent.context,56),-2))
    val name=TextView(parent.context).apply{setTextColor(Color.WHITE);textSize=16f;isSingleLine=true}
    row.addView(name,LinearLayout.LayoutParams(0,-2,1f))
    return Holder(row,number,name)
  }

  override fun getItemCount()=visible.size
  override fun onBindViewHolder(h:Holder,p:Int){
    val global=visible[p];val ch=all[global]
    h.number.text="%03d".format(global+1)
    h.name.text=ch.name+"   "+ch.category
    h.itemView.setBackgroundColor(Color.argb(120,11,17,27))
    h.itemView.setOnClickListener{select(global)}
    h.itemView.setOnFocusChangeListener{v,f->
      v.setBackgroundColor(if(f)Color.argb(120,30,100,80) else Color.argb(120,11,17,27))
      v.animate().scaleX(if(f)1.02f else 1f).scaleY(if(f)1.02f else 1f).setDuration(90).start()
    }
  }

  class Holder(v:View,val number:TextView,val name:TextView):RecyclerView.ViewHolder(v)
  companion object{fun dp(c:Context,v:Int)=(v*c.resources.displayMetrics.density).toInt()}
}
