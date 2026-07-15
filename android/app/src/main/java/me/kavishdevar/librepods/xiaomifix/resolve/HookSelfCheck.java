package me.kavishdevar.librepods.xiaomifix.resolve;

public final class HookSelfCheck {
    private HookSelfCheck() {
    }

    public interface LogSink {
        void info(String message);

        void warn(String message);
    }

    public static void log(ResolveResult result, LogSink log) {
        log.info("hook self-check begin fingerprint=" + result.getFingerprint());
        for (ResolvedTarget target : result.getTargets().values()) {
            String status = target.isResolved() ? "OK" : "MISS";
            String className = target.isResolved()
                    ? target.getClazz().getName()
                    : (target.getClassName() == null ? "-" : target.getClassName());
            String line = "[" + status + "] " + target.getId()
                    + " source=" + target.getSource()
                    + " class=" + className;
            if (target.isResolved()) {
                log.info(line);
            } else if (target.isCritical()) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }

        int hooks = result.resolvedCount();
        int total = result.getTargets().size();
        int critical = result.criticalResolvedCount();
        int criticalTotal = result.criticalTotal();
        String protection;
        if (critical == criticalTotal) {
            protection = "FULL";
        } else if (critical > 0) {
            protection = "PARTIAL";
        } else {
            protection = "DEGRADED";
        }
        log.info("hook self-check end hooks=" + hooks + "/" + total
                + " critical=" + critical + "/" + criticalTotal
                + " protection=" + protection);
    }
}
