package com.gamingmesh.jobs.hooks.Curvature;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import com.gamingmesh.jobs.container.JobsPlayer;

import net.Zrips.CMILib.Equations.Parser;
import net.Zrips.CMILib.Messages.CMIMessages;

/**
 * Bridge to the Curvature service.
 */
public class CurvatureHook {

    private Object service;
    private Method curveNamesMethod;
    private Method allMultipliersMethod;
    private Method allXMethod;
    private Method multiplierMethod;
    private Method rawXMethod;
    private boolean serviceMissingLogged;
    private boolean methodMissingLogged;
    private long lastSqlError;
    private Set<String> cachedCurveNames = Collections.emptySet();

    public void applyCurveVariables(Parser parser, JobsPlayer jPlayer) {
        if (parser == null || jPlayer == null)
            return;

        Object svc = resolveService();
        if (svc == null)
            return;

        Set<String> curveNames = getCurveNames(svc);
        if (curveNames.isEmpty())
            return;

        // Set defaults so equations still evaluate even if Curvature lookups fail later.
        for (String curve : curveNames) {
            String token = toToken(curve);
            parser.setVariable("curve_" + token, 0D);
            parser.setVariable("curve_" + token + "_x", 0D);
        }

        try {
            UUID playerId = jPlayer.getUniqueId();
            Map<String, Double> multipliers = getValueMap(svc, allMultipliersMethod, multiplierMethod, playerId, curveNames);
            Map<String, Double> rawValues = getValueMap(svc, allXMethod, rawXMethod, playerId, curveNames);

            for (String curve : curveNames) {
                String token = toToken(curve);
                parser.setVariable("curve_" + token, multipliers.getOrDefault(curve, 0D));
                parser.setVariable("curve_" + token + "_x", rawValues.getOrDefault(curve, 0D));
            }
        } catch (SQLException e) {
            logSqlError(e);
        } catch (ReflectiveOperationException e) {
            logMethodError(e);
        }
    }

    private Object resolveService() {
        if (service != null)
            return service;

        ServicesManager servicesManager = Bukkit.getServicesManager();
        for (Class<?> known : servicesManager.getKnownServices()) {
            if (!"CurvatureService".equals(known.getSimpleName()))
                continue;

            cacheMethods(known);
            RegisteredServiceProvider<?> registration = servicesManager.getRegistration(known);
            if (registration != null) {
                service = registration.getProvider();
                serviceMissingLogged = false;
                return service;
            }
        }

        if (!serviceMissingLogged) {
            CMIMessages.consoleMessage("&cCurvature service not found. Curve tokens will stay at 0 until Curvature is available.");
            serviceMissingLogged = true;
        }
        return null;
    }

    private void cacheMethods(Class<?> known) {
        methodMissingLogged = false;
        try {
            curveNamesMethod = known.getMethod("getCurveNames");
        } catch (NoSuchMethodException ignored) {
        }

        try {
            allMultipliersMethod = known.getMethod("getAllMultipliers", UUID.class);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            allXMethod = known.getMethod("getAllX", UUID.class);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            multiplierMethod = known.getMethod("getMultiplier", UUID.class, String.class);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            rawXMethod = known.getMethod("getRawX", UUID.class, String.class);
        } catch (NoSuchMethodException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> getCurveNames(Object svc) {
        if (curveNamesMethod == null) {
            if (!methodMissingLogged) {
                CMIMessages.consoleMessage("&cCurvature service is missing getCurveNames(), curve variables will stay at 0.");
                methodMissingLogged = true;
            }
            return cachedCurveNames;
        }

        try {
            Object result = curveNamesMethod.invoke(svc);
            if (result instanceof Set) {
                cachedCurveNames = new HashSet<>((Set<String>) result);
                return cachedCurveNames;
            }
        } catch (InvocationTargetException e) {
            logSqlIfNeeded(e.getCause());
        } catch (IllegalAccessException e) {
            logMethodError(e);
        }
        return cachedCurveNames;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> getValueMap(Object svc, Method bulkMethod, Method singleMethod, UUID playerId, Set<String> curveNames)
        throws ReflectiveOperationException, SQLException {

        if (bulkMethod != null) {
            try {
                Object result = bulkMethod.invoke(svc, playerId);
                if (result instanceof Map) {
                    return new HashMap<>((Map<String, Double>) result);
                }
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SQLException)
                    throw (SQLException) cause;
                throw e;
            }
        }

        Map<String, Double> values = new HashMap<>();
        if (singleMethod == null || curveNames.isEmpty())
            return values;

        for (String curve : curveNames) {
            try {
                Object res = singleMethod.invoke(svc, playerId, curve);
                if (res instanceof Number) {
                    values.put(curve, ((Number) res).doubleValue());
                }
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SQLException)
                    throw (SQLException) cause;
                throw e;
            }
        }
        return values;
    }

    private void logSqlIfNeeded(Throwable cause) {
        if (cause instanceof SQLException)
            logSqlError((SQLException) cause);
    }

    private void logSqlError(SQLException e) {
        long now = System.currentTimeMillis();
        if (now - lastSqlError < 1000)
            return;
        lastSqlError = now;
        CMIMessages.consoleMessage("&cFailed to query Curvature data: " + e.getMessage());
    }

    private void logMethodError(Exception e) {
        if (!methodMissingLogged) {
            CMIMessages.consoleMessage("&cCurvature API mismatch: " + e.getMessage());
            methodMissingLogged = true;
        }
    }

    private String toToken(String curve) {
        String cleaned = curve == null ? "" : curve.trim().toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "_");
        if (cleaned.isEmpty())
            return "curve";
        return cleaned;
    }
}
