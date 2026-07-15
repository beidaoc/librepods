package me.kavishdevar.librepods.xiaomifix.resolve;

public final class ResolvedTarget {
    private final String id;
    private final String className;
    private final ResolveSource source;
    private final boolean critical;
    private Class<?> clazz;

    public ResolvedTarget(String id, String className, ResolveSource source, boolean critical) {
        this.id = id;
        this.className = className;
        this.source = source;
        this.critical = critical;
    }

    public String getId() {
        return id;
    }

    public String getClassName() {
        return className;
    }

    public ResolveSource getSource() {
        return source;
    }

    public boolean isCritical() {
        return critical;
    }

    public Class<?> getClazz() {
        return clazz;
    }

    public void bind(Class<?> clazz) {
        this.clazz = clazz;
    }

    public boolean isResolved() {
        return clazz != null;
    }
}
