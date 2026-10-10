package com.xtremex.tv.ads;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdPolicyTest {
 private final String app="ca-app-pub-1234567890123456~1234567890";
 private final String unit="ca-app-pub-1234567890123456/1234567890";
 @Test public void tvPremiumBlockedAndDisabledNeverRequestAds() {
  assertNull(AdPolicy.banner(true,true,true,true,true,app,"",""));
  assertNull(AdPolicy.banner(false,true,false,true,true,app,"",""));
  assertNull(AdPolicy.banner(false,false,true,true,true,app,"",""));
  assertNull(AdPolicy.banner(false,true,true,false,true,app,"",""));
 }
 @Test public void testModeUsesOnlyGoogleSampleBanner() {
  assertEquals("ca-app-pub-3940256099942544/6300978111",AdPolicy.banner(false,true,true,true,true,app,"",unit));
 }
 @Test public void liveRequiresCompiledAppAndMatchingPublisher() {
  assertEquals(unit,AdPolicy.banner(false,true,true,true,false,app,app,unit));
  assertNull(AdPolicy.banner(false,true,true,true,false,AdPolicy.TEST_APP,app,unit));
  assertNull(AdPolicy.banner(false,true,true,true,false,app,app,"ca-app-pub-9876543210987654/1234567890"));
  assertNull(AdPolicy.banner(false,true,true,true,false,AdPolicy.TEST_APP,AdPolicy.TEST_APP,AdPolicy.TEST_BANNER));
  assertNull(AdPolicy.banner(false,true,true,true,false,app,app,"bad"));
 }
}
