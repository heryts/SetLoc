package android.content;

public class ComponentName {
    private final String pkg;

    public ComponentName(String pkg, String cls) {
        this.pkg = pkg;
    }

    public String getPackageName() {
        return pkg;
    }
}
