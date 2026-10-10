package com.xtremex.tv.ads;
/** Pure eligibility gate shared by initial requests and asynchronous callbacks. */
public final class AdPolicy {
 public static final String TEST_APP="ca-app-pub-3940256099942544~3347511713";
 public static final String TEST_BANNER="ca-app-pub-3940256099942544/6300978111";
 private AdPolicy() {}
 public static String banner(boolean tv, boolean approved, boolean free, boolean enabled,
   boolean testMode, String compiledApp, String configuredApp, String unit) {
  if(tv||!approved||!free||!enabled)return null;
  if(testMode)return TEST_BANNER;
  if(compiledApp.equals(TEST_APP)||!compiledApp.equals(configuredApp)
   ||!compiledApp.matches("ca-app-pub-[0-9]{16}~[0-9]{10}")
   ||!unit.matches("ca-app-pub-[0-9]{16}/[0-9]{10}")
   ||!compiledApp.substring(0,27).equals(unit.substring(0,27)))return null;
  return unit;
 }
}
