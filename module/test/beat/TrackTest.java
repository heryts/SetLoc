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
    SystemClock.fake=now; st.put(Config.KEY,Config.enc(cfg)); if(trk==null) st.remove(Config.KEY_TRK); else st.put(Config.KEY_TRK,trk);
    Config.refresh(null,null,null);
  }
  public static void main(String[] x) throws Exception {
    ConfigNative.ensureLoaded(); LocationNative.ensureLoaded();
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
    near("tick 0: lat", Config.pos.lat, -7.0, 1e-9); near("tick 0: lng", Config.pos.lng, 110.0, 1e-9);
    eq("tick 0: enabled", Config.enabled, true);
    SystemClock.fake=T0+250; Config.tick();
    near("setengah tick: lat interpolasi", Config.pos.lat, -7.05, 1e-9);
    near("setengah tick: lng interpolasi", Config.pos.lng, 110.1, 1e-9);
    near("setengah tick: spd interpolasi", Config.spd, 5.0, 1e-4);
    near("setengah tick: alt interpolasi", Config.alt, 15.0, 1e-9);
    near("bearing 350->10 lewat 0 (bukan 180)", Config.brg, 0.0, 1e-3);
    SystemClock.fake=T0+500; Config.tick();
    near("tick 1 tepat: lat", Config.pos.lat, -7.1, 1e-9); near("tick 1: brg", Config.brg, 10.0, 1e-3);
    SystemClock.fake=T0+900; Config.tick();
    near("antara tick 1 dan 2: lat", Config.pos.lat, -7.18, 1e-9);
    SystemClock.fake=T0+30; Config.tick(); SystemClock.fake=T0+60; Config.tick();
    near("waktu mundur: kembali ke posisi tick awal+", Config.pos.lat, -7.006, 1e-9);
    SystemClock.fake=T0+1000; Config.tick();
    near("akhir jendela: tahan tick terakhir", Config.pos.lat, -7.2, 1e-9);
    eq("akhir jendela: masih enabled", Config.enabled, true);
    eq("akhir jendela: spd masih tick terakhir (<3 dtk)", Config.spd, 10f);

    SystemClock.fake=T0+1000+3500; Config.tick();
    eq("terlambat 3,5 dtk: spd=0", Config.spd, 0f); eq("terlambat 3,5 dtk: masih enabled", Config.enabled, true);
    near("terlambat: posisi tertahan di titik akhir", Config.pos.lat, -7.2, 1e-9);
    SystemClock.fake=T0+1000+59000; Config.tick();
    eq("terlambat 59 dtk: enabled", Config.enabled, true);
    SystemClock.fake=T0+1000+61000; Config.tick();
    eq("terlambat 61 dtk: spoof mati", Config.enabled, false);
    long N=T0+1000+62000;
    String rows2=",-8.000000,111.000000,3.000,45.00,50.00|,-8.100000,111.100000,3.000,45.00,50.00|";
    refresh(st,N,CFG,trk(N,N,500,rows2));
    eq("jendela baru: enabled kembali", Config.enabled, true);
    near("jendela baru: lat", Config.pos.lat, -8.0, 1e-9); eq("jendela baru: spd", Config.spd, 3f);

    long H=N+600000;
    refresh(st,H,CFG,trk(N+500,H-5000,500,",-8.100000,111.100000,0.000,45.00,50.00|"));
    eq("hold: jendela n=1 dengan a baru -> enabled", Config.enabled, true);
    near("hold: posisi titik akhir", Config.pos.lat, -8.1, 1e-9); eq("hold: spd 0", Config.spd, 0f);
    refresh(st,H+30000,CFG,trk(N+500,H-5000,500,",-8.100000,111.100000,0.000,45.00,50.00|"));
    eq("hold basi 35 dtk: masih enabled", Config.enabled, true);
    refresh(st,H+70000,CFG,trk(N+500,H-5000,500,",-8.100000,111.100000,0.000,45.00,50.00|"));
    eq("hold basi 75 dtk: spoof mati", Config.enabled, false);

    long M=H+100000;
    refresh(st,M,CFG,null);
    eq("tk tanpa jendela: pakai nilai cfg (titik awal)", Config.pos.lat, -7.0); eq("tk tanpa jendela: enabled", Config.enabled, true); eq("tk tanpa jendela: spd 0", Config.spd, 0f);
    refresh(st,M+61000,CFG,null);
    eq("tk tanpa jendela 61 dtk: mati", Config.enabled, false);
    refresh(st,M+62000,CFG,trk(M+62000,M+62000,500,rows2));
    eq("jendela datang menyusul: hidup lagi", Config.enabled, true);

    long L=M+200000;
    refresh(st,L,"enabled=1;lat=-6.5;lng=106.5;acc=10;random=0;spd=7;alt=1;altv=0;rr=8;brg=30;pl=1",trk(L,L,500,rows2));
    eq("cfg tanpa tk: jendela diabaikan", Config.tkMode, false);
    near("cfg tanpa tk: lat dari cfg", Config.pos.lat, -6.5, 1e-9); eq("cfg tanpa tk: spd dari cfg", Config.spd, 7f);
    refresh(st,L+500,CFG,trk(L,L,500,rows2));
    near("kembali ke tk: lat dari jendela (tick 1)", Config.pos.lat, -8.1, 1e-9);

    SystemClock.fake=L+700000;
    st.put(Config.KEY,Config.enc("enabled=1;lat=-6.5;lng=106.5;acc=10;random=0;spd=7;alt=1;altv=0;rr=8;pl=1;tk=1"));
    Config.refresh(null,null,null);
    eq("tk di akhir cfg juga dikenali", Config.tkMode, true);

    SystemClock.fake+=10;
    refresh(st,SystemClock.fake,"enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8",null);
    eq("tanpa pl dan tk: snapshot memakai lat cfg", Config.snapshot()!=null, true);
    long A=M+400000;
    String rowsA=",10.000000,179.900000,5.000,90.00,0.00|,10.000000,-179.900000,5.000,90.00,0.00|";
    refresh(st,A,CFG,trk(A,A,500,rowsA));
    SystemClock.fake=A+250; Config.tick();
    near("meridian 180: setengah tick tidak melompat lewat 0", Math.abs(Config.pos.lng), 180.0, 1e-6);
    SystemClock.fake=A+125; Config.tick();
    near("meridian 180: seperempat tick", Config.pos.lng, 179.95, 1e-6);
    SystemClock.fake=A+375; Config.tick();
    near("meridian 180: tiga perempat tick", Config.pos.lng, -179.95, 1e-6);

    {
      java.io.File tf=java.io.File.createTempFile("gpsz_trk",".txt"); tf.deleteOnExit(); Config.trkFile=tf.getPath();
      long F0=2000000; StringBuilder full=new StringBuilder("t="+F0+";k=500;n=200\n");
      StringBuilder win=new StringBuilder(",");
      for(int i=0;i<200;i++){ String ln=String.format(java.util.Locale.US,"%11.6f %11.6f %7.3f %6.2f %8.2f\n",-7.0-i*0.001,110.0+i*0.001,5.0,90.0,10.0); full.append(ln); if(i>=10&&i<13) win.append(String.format(java.util.Locale.US,"%.6f,%.6f,5.000,90.00,10.00|,",-7.0-i*0.001,110.0+i*0.001)); }
      java.nio.file.Files.write(tf.toPath(),full.toString().getBytes("US-ASCII"));
      String w="t="+(F0+10*500)+";k=500;a="+(F0+5000)+";d="+win;
      refresh(st,F0+5000,CFG,w);
      SystemClock.fake=F0+5000+2000; Config.tick();
      near("file: jendela habis, posisi tetap berjalan dari timeline penuh (tick 14)",Config.pos.lat,-7.014,1e-9);
      eq("file: jendela habis, masih enabled",Config.enabled,true); eq("file: spd tidak 0",Config.spd,5f);
      SystemClock.fake=F0+60000; Config.tick();
      near("file: tick 120",Config.pos.lat,-7.120,1e-9); eq("file: 55 dtk setelah jendela, enabled",Config.enabled,true);
      SystemClock.fake=F0+99500; Config.tick();
      near("file: tick terakhir",Config.pos.lat,-7.199,1e-9);
      SystemClock.fake=F0+99500+3500; Config.tick();
      eq("file: lewat akhir rute 3,5 dtk: spd 0",Config.spd,0f); eq("file: lewat akhir rute 3,5 dtk: enabled",Config.enabled,true);
      SystemClock.fake=F0+99500+61000; Config.tick();
      eq("file: lewat akhir rute 61 dtk tanpa daemon: mati",Config.enabled,false);
      String bad="t="+(F0+10*500)+";k=500;a="+(F0+5000)+";d=,-1.000000,1.000000,5.000,90.00,10.00|,";
      refresh(st,F0+6000,CFG,bad);
      near("file tidak cocok dengan jendela: pakai jendela",Config.pos.lat,-1.0,1e-9);
      tf.delete();
      refresh(st,F0+7000,CFG,w);
      near("file hilang: pakai jendela",Config.pos.lat,-7.012,1e-9);
      java.nio.file.Files.write(tf.toPath(),"t=1;k=500;n=3\n1 2\n".getBytes("US-ASCII"));
      refresh(st,F0+8000,CFG,w+"x");
      eq("file rusak: diabaikan, tetap hidup",Config.enabled,true);
      tf.delete();
      long R=F0+400000; refresh(st,R,CFG,w); SystemClock.up=-1;
      SystemClock.fake=R+30000; Config.tick();
      SystemClock.fake=R+130000; SystemClock.up=R+31000; Config.tick();
      eq("tidur (suspend) 100 dtk: spoof tidak mati, daemon diberi masa tunggu baru",Config.enabled,true);
      SystemClock.fake=R+130000+4000; SystemClock.up=R+35000; Config.tick();
      eq("setelah bangun 4 dtk: spd 0",Config.spd,0f); eq("setelah bangun 4 dtk: masih enabled",Config.enabled,true);
      SystemClock.fake=R+130000+62000; SystemClock.up=R+93000; Config.tick();
      eq("setelah bangun 62 dtk tanpa daemon: mati",Config.enabled,false);
      SystemClock.up=-1;
          }
    if(fails>0){ System.out.println(fails+" GAGAL"); System.exit(1); }
    System.out.println("ALL PASS");
  }
}
