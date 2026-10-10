# AdMob setup

AdMob is integrated only on mobile, in a dedicated banner slot in Settings. Android TV is unsupported by Google Mobile Ads. Premium users do not request AdMob ads; Free accounts must have a valid approved device-bound lease. Playback size is unchanged. Donation and sponsor settings are independent.

Defaults: AdMob off, test mode on. Test ads never generate revenue. The first release uses Google's sample App ID because the owner has not supplied real IDs. Switching this APK to Live mode cannot generate live requests.

To activate revenue:
1. Create an Android app in your AdMob account and a Banner ad unit for package `com.xtremex.tv`.
2. Complete applicable app verification/review and Privacy & messaging setup in AdMob. Earnings/payouts are managed in AdMob, not this admin panel.
3. Build the next signed APK using the release workflow's `admob_app_id` input (or local `ADMOB_APP_ID`). An App ID change needs a new APK; a banner ID change does not.
4. Admin Settings → Google AdMob: paste that App ID and its Banner Ad Unit ID, enable banners, turn off Test mode, Save.
5. Users → select the ID → Make Free. Premium remains ad-free. Test only with Google's sample units or registered test devices. Do not click your own live ads.

Consent: UMP refresh is deferred until a user opens eligible ad content, before SDK initialization/request. Privacy options remain available after a consent message has made them required, even if the user becomes Premium. TV never initializes the SDK or UMP. No ad is requested after Settings closes, the app stops, access expires, or plan becomes Premium. SDK 25.4 preserves minSdk23; 25.5 needs API24.

Limitations: actual Google ad fill/account readiness must be checked on a mobile device. Current production config stays disabled until owner setup. Sponsor cards remain available on TV as configured.

## Official live sports shortcuts
Settings → Live sports opens Tapmad Sports/Live TV and FanCode Live Events in their own service; the event lists update at the providers. These are browser destinations, not embedded streams in the playlist. Provider credentials or rights are needed for direct playable channels. On TV without a browser the app displays the destination for a phone.
