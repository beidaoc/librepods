package me.kavishdevar.librepods.xiaomifix.resolve;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ResolveResult {
    private final String fingerprint;
    private final Map<String, ResolvedTarget> targets = new LinkedHashMap<>();

    public ResolveResult(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void put(ResolvedTarget target) {
        targets.put(target.getId(), target);
    }

    public ResolvedTarget get(String id) {
        return targets.get(id);
    }

    public Class<?> getClass(String id) {
        ResolvedTarget target = targets.get(id);
        return target == null ? null : target.getClazz();
    }

    public Map<String, ResolvedTarget> getTargets() {
        return targets;
    }

    public int resolvedCount() {
        int count = 0;
        for (ResolvedTarget target : targets.values()) {
            if (target.isResolved()) {
                count++;
            }
        }
        return count;
    }

    public int criticalResolvedCount() {
        int count = 0;
        for (String id : HookIds.criticalIds()) {
            ResolvedTarget target = targets.get(id);
            if (target != null && target.isResolved()) {
                count++;
            }
        }
        return count;
    }

    public int criticalTotal() {
        return HookIds.criticalIds().length;
    }
}
