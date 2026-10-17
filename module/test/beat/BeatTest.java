package io.gpsz.zygote;
import android.os.SystemClock;
public class BeatTest {
  static int fails=0;
  static void eq(String n, Object a, Object b){ if(!a.equals(b)){fails++; System.out.println("FAIL "+n+": "+a+" != "+b);} else System.out.println("ok   "+n); }
  static void poll(long t,String cfg,String beat){ SystemClock.fake=t; Config.parse(cfg); Config.checkBeat(beat); }
  public static void main(String[] x){
    String PL="enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;spd=33.0;alt=1;altv=0;rr=8;brg=30;pl=1";
    String SP="enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;spd=0;alt=1;altv=0;rr=8";
    long t=1000;
    // normal: beat changes each second
    for(int i=0;i<20;i++){ poll(t,PL,""+(i/4)); t+=250; }
    eq("normal enabled",Config.enabled,true); eq("normal spd",Config.spd,33.0f);
    // daemon dies: beat constant "5"; 9s later still fine, then freeze at 10s, off at 60s
    String last="4"; long t0=t;
    for(;t<t0+9000;t+=250) poll(t,PL,last);
    eq("9s stale: still moving",Config.spd,33.0f);
    for(;t<t0+11000;t+=250) poll(t,PL,last);
    eq("11s stale: frozen spd=0",Config.spd,0f); eq("11s stale: still enabled",Config.enabled,true);
    for(;t<t0+59000;t+=250) poll(t,PL,last);
    eq("59s: enabled",Config.enabled,true);
    for(;t<t0+61000;t+=250) poll(t,PL,last);
    eq("61s: spoof off",Config.enabled,false);
    // daemon restarted by supervisor -> beat changes -> resumes
    poll(t,PL,"900"); t+=250; poll(t,PL,"900");
    eq("resume enabled",Config.enabled,true); eq("resume spd",Config.spd,33.0f);
    // process freeze gap: 30s no polls must NOT count as stale
    t0=t; for(;t<t0+3000;t+=250) poll(t,PL,"900");   // 3s stale
    t+=30000; poll(t,PL,"900");                      // gap
    eq("after gap: not frozen",Config.spd,33.0f); eq("after gap: enabled",Config.enabled,true);
    // single point spoof never touched, even with ancient beat
    t0=t; for(;t<t0+120000;t+=250) poll(t,SP,"1");
    eq("single point 120s: enabled",Config.enabled,true);
    // pl=1 appears with an old leftover beat: grace starts at first sight
    t+=250; poll(t,PL,"1"); eq("first pl poll enabled",Config.enabled,true); eq("first pl poll spd",Config.spd,33.0f);
    // stop button: cfg enabled=0 -> off at once
    t+=250; poll(t,"enabled=0;lat=-7.7;lng=110.4;acc=10;random=0","1"); eq("stop",Config.enabled,false);
    // missing beat key while playing -> treated as constant, goes stale
    t0=t; Config.parse(PL); for(;t<t0+12000;t+=250) poll(t,PL,null);
    eq("null beat 12s -> frozen",Config.spd,0f);
    // snapshot: tanpa jitter = koordinat apa adanya; dipakai ulang < 200 ms; baru sesudahnya; jitter tetap dalam radius rr
    SystemClock.fake=500000; Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;rr=8");
    Config.Pt a=Config.snapshot(); SystemClock.fake+=150; Config.Pt b=Config.snapshot();
    eq("snapshot tanpa jitter lat",a.lat,-7.7); eq("snapshot tanpa jitter lng",a.lng,110.4);
    eq("snapshot < 200 ms dipakai ulang",a==b,true);
    SystemClock.fake+=100; eq("snapshot >= 200 ms diperbarui",Config.snapshot()!=a,true);
    SystemClock.fake+=1000; Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8");
    boolean inside=true; for(int i=0;i<200;i++){ SystemClock.fake+=250; Config.Pt p=Config.snapshot();
      if(Math.abs(p.lat+7.7)*111320.0>8.001 || Math.abs(p.lng-110.4)*111320.0*Math.cos(Math.toRadians(-7.7))>8.001) inside=false; }
    eq("snapshot jitter dalam radius rr",inside,true);
    System.out.println(fails==0?"ALL PASS":"FAILURES "+fails); System.exit(fails);
  }
}
