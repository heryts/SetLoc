package io.gpsz.zygote;
import android.os.SystemClock;
import java.util.Map;
public class TrackTest {
  static int fails=0;
  static void eq(String n, Object a, Object b){ if(!java.util.Objects.equals(a,b)){fails++; System.out.println("FAIL "+n+": "+a+" != "+b);} else System.out.println("ok   "+n); }
  static void near(String n, double a, double b, double eps){ eq(n, Math.abs(a-b)<=eps, true); if(Math.abs(a-b)>eps) System.out.println("     got "+a+" want "+b); }
  static final String CFG="enabled=1;lat=-7.0;lng=110.0;acc=10;random=0;spd=0;alt=5;altv=0;rr=8;tk=1;pl=1";
  static String trk(long t0,long a,int k,String rows){ return "t="+t0+";k="+k+";a="+a+";d="+rows; }
  static void refresh(Map<String,String> st,long now,String cfg,String trk){
    SystemClock.fake=now; st.put("gpsz_cfg",cfg); if(trk==null) st.remove("gpsz_trk"); else st.put("gpsz_trk",trk);
    Config.refresh(null,null,null);
  }
  public static void main(String[] x){
    Map<String,String> st=android.provider.Settings.Global.store;
    String rows=",-7.000000,110.000000,0.000,350.00,10.00|,-7.100000,110.200000,10.000,10.00,20.00|,-7.200000,110.400000,10.000,10.00,30.00|";
    Config.Track t=Config.parseTrack(trk(100000,100000,500,rows));
    eq("parseTrack: 3 baris, baris awal berkoma diabaikan", t!=null&&t.n==3, true);
    eq("parseTrack: nilai baris kedua", t.la[1]+","+t.lo[1]+","+t.sp[1]+","+t.br[1]+","+t.al[1], "-7.1,110.2,10.0,10.0,20.0");
    eq("parseTrack: data rusak -> null", Config.parseTrack(trk(1,1,500,",1,2,3|")), null);
    eq("parseTrack: angka rusak -> null", Config.parseTrack(trk(1,1,500,",a,b,c,d,e|")), null);
    eq("parseTrack: k<=0 -> null", Config.parseTrack(trk(1,1,0,rows)), null);
    eq("parseTrack: null -> null", Config.parseTrack(null), null);

    long T0=100000;
    refresh(st,T0,CFG,trk(T0,T0,500,rows));
    eq("tk: aktif", Config.tkMode, true);
    near("tick 0: lat", Config.lat, -7.0, 1e-9); near("tick 0: lng", Config.lng, 110.0, 1e-9);
    eq("tick 0: enabled", Config.enabled, true);
    SystemClock.fake=T0+250; Config.tick();
    near("setengah tick: lat interpolasi", Config.lat, -7.05, 1e-9);
    near("setengah tick: lng interpolasi", Config.lng, 110.1, 1e-9);
    near("setengah tick: spd interpolasi", Config.spd, 5.0, 1e-4);
    near("setengah tick: alt interpolasi", Config.alt, 15.0, 1e-9);
    near("bearing 350->10 lewat 0 (bukan 180)", Config.brg, 0.0, 1e-3);
    SystemClock.fake=T0+500; Config.tick();
    near("tick 1 tepat: lat", Config.lat, -7.1, 1e-9); near("tick 1: brg", Config.brg, 10.0, 1e-3);
    SystemClock.fake=T0+900; Config.tick();
    near("antara tick 1 dan 2: lat", Config.lat, -7.18, 1e-9);
    SystemClock.fake=T0+30; Config.tick(); SystemClock.fake=T0+60; Config.tick();
    near("waktu mundur: kembali ke posisi tick awal+", Config.lat, -7.006, 1e-9);
    SystemClock.fake=T0+1000; Config.tick();
    near("akhir jendela: tahan tick terakhir", Config.lat, -7.2, 1e-9);
    eq("akhir jendela: masih enabled", Config.enabled, true);
    eq("akhir jendela: spd masih tick terakhir (<3 dtk)", Config.spd, 10f);

    SystemClock.fake=T0+1000+3500; Config.tick();
    eq("terlambat 3,5 dtk: spd=0", Config.spd, 0f); eq("terlambat 3,5 dtk: masih enabled", Config.enabled, true);
    near("terlambat: posisi tertahan di titik akhir", Config.lat, -7.2, 1e-9);
    SystemClock.fake=T0+1000+59000; Config.tick();
    eq("terlambat 59 dtk: enabled", Config.enabled, true);
    SystemClock.fake=T0+1000+61000; Config.tick();
    eq("terlambat 61 dtk: spoof mati", Config.enabled, false);
    long N=T0+1000+62000;
    String rows2=",-8.000000,111.000000,3.000,45.00,50.00|,-8.100000,111.100000,3.000,45.00,50.00|";
    refresh(st,N,CFG,trk(N,N,500,rows2));
    eq("jendela baru: enabled kembali", Config.enabled, true);
    near("jendela baru: lat", Config.lat, -8.0, 1e-9); eq("jendela baru: spd", Config.spd, 3f);

    long H=N+600000;
    refresh(st,H,CFG,trk(N+500,H-5000,500,",-8.100000,111.100000,0.000,45.00,50.00|"));
    eq("hold: jendela n=1 dengan a baru -> enabled", Config.enabled, true);
    near("hold: posisi titik akhir", Config.lat, -8.1, 1e-9); eq("hold: spd 0", Config.spd, 0f);
    refresh(st,H+30000,CFG,trk(N+500,H-5000,500,",-8.100000,111.100000,0.000,45.00,50.00|"));
    eq("hold basi 35 dtk: masih enabled", Config.enabled, true);
    refresh(st,H+70000,CFG,trk(N+500,H-5000,500,",-8.100000,111.100000,0.000,45.00,50.00|"));
    eq("hold basi 75 dtk: spoof mati", Config.enabled, false);

    long M=H+100000;
    refresh(st,M,CFG,null);
    eq("tk tanpa jendela: pakai nilai cfg (titik awal)", Config.lat, -7.0); eq("tk tanpa jendela: enabled", Config.enabled, true); eq("tk tanpa jendela: spd 0", Config.spd, 0f);
    refresh(st,M+61000,CFG,null);
    eq("tk tanpa jendela 61 dtk: mati", Config.enabled, false);
    refresh(st,M+62000,CFG,trk(M+62000,M+62000,500,rows2));
    eq("jendela datang menyusul: hidup lagi", Config.enabled, true);

    long L=M+200000;
    refresh(st,L,"enabled=1;lat=-6.5;lng=106.5;acc=10;random=0;spd=7;alt=1;altv=0;rr=8;brg=30;pl=1",trk(L,L,500,rows2));
    eq("cfg tanpa tk: jendela diabaikan", Config.tkMode, false);
    near("cfg tanpa tk: lat dari cfg", Config.lat, -6.5, 1e-9); eq("cfg tanpa tk: spd dari cfg", Config.spd, 7f);
    refresh(st,L+500,CFG,trk(L,L,500,rows2));
    near("kembali ke tk: lat dari jendela (tick 1)", Config.lat, -8.1, 1e-9);

    SystemClock.fake=L+700000;
    st.put("gpsz_cfg","enabled=1;lat=-6.5;lng=106.5;acc=10;random=0;spd=7;alt=1;altv=0;rr=8;pl=1;tk=1");
    Config.refresh(null,null,null);
    eq("tk di akhir cfg juga dikenali", Config.tkMode, true);

    SystemClock.fake+=10;
    refresh(st,SystemClock.fake,"enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8",null);
    eq("tanpa pl dan tk: snapshot memakai lat cfg", Config.snapshot()!=null, true);
    if(fails>0){ System.out.println(fails+" GAGAL"); System.exit(1); }
    System.out.println("ALL PASS");
  }
}
