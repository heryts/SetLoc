package android.content.pm;

public class VersionedPackage {
    private final String pkg;

    public VersionedPackage(String pkg, int version) {
        this.pkg = pkg;
    }

    public String getPackageName() {
        return pkg;
    }
}
