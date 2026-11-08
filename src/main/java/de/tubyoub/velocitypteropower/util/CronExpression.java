/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.util;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A small dependency-free parser for standard 5-field cron expressions:
 * {@code minute hour day-of-month month day-of-week}.
 *
 * <p>Supported per field: {@code *}, lists ({@code 1,15}), ranges
 * ({@code 9-17}), steps ({@code *}{@code /15}, {@code 0-30/10}) and month /
 * day-of-week names ({@code JAN-DEC}, {@code SUN-SAT}; {@code 7} is Sunday,
 * like {@code 0}). Day-of-month and day-of-week follow the usual Vixie cron
 * rule: when both fields are restricted (not {@code *}), a time matches when
 * <em>either</em> matches; when only one is restricted, only that one decides.
 *
 * <p>No seconds field, no {@code L}/{@code W}/{@code #} specials, no
 * {@code @daily} shortcuts — keep the config format to the 5-field form
 * documented in {@code config.yml}.
 */
public final class CronExpression {

    private static final Map<String, Integer> MONTH_NAMES = new HashMap<>();
    private static final Map<String, Integer> DOW_NAMES = new HashMap<>();

    static {
        String[] months = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        for (int i = 0; i < months.length; i++) MONTH_NAMES.put(months[i], i + 1);
        String[] days = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
        for (int i = 0; i < days.length; i++) DOW_NAMES.put(days[i], i);
    }

    private final String expression;
    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;

    private CronExpression(String expression, Set<Integer> minutes, Set<Integer> hours,
                           Set<Integer> daysOfMonth, Set<Integer> months, Set<Integer> daysOfWeek,
                           boolean dayOfMonthRestricted, boolean dayOfWeekRestricted) {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthRestricted = dayOfMonthRestricted;
        this.dayOfWeekRestricted = dayOfWeekRestricted;
    }

    /**
     * Parses a 5-field cron expression.
     *
     * @param expression the raw config value, e.g. {@code "0 3 * * *"}
     * @return the parsed expression
     * @throws IllegalArgumentException if the expression is blank, has the wrong
     *         number of fields, or contains an out-of-range value
     */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron expression is blank");
        }
        String trimmed = expression.trim();
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                    "cron expression '" + trimmed + "' must have exactly 5 fields "
                            + "(minute hour day-of-month month day-of-week), found " + fields.length);
        }
        return new CronExpression(
                trimmed,
                parseField(fields[0], 0, 59, null, "minute"),
                parseField(fields[1], 0, 23, null, "hour"),
                parseField(fields[2], 1, 31, null, "day-of-month"),
                parseField(fields[3], 1, 12, MONTH_NAMES, "month"),
                parseField(fields[4], 0, 7, DOW_NAMES, "day-of-week"),
                isRestricted(fields[2]),
                isRestricted(fields[4]));
    }

    /** Returns true if the given moment (already in the schedule's timezone) matches this expression. */
    public boolean matches(ZonedDateTime time) {
        if (!minutes.contains(time.getMinute())) return false;
        if (!hours.contains(time.getHour())) return false;
        if (!months.contains(time.getMonthValue())) return false;
        boolean domMatches = daysOfMonth.contains(time.getDayOfMonth());
        // DayOfWeek: MONDAY=1 .. SUNDAY=7 -> cron 0=Sunday
        boolean dowMatches = daysOfWeek.contains(time.getDayOfWeek().getValue() % 7);
        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            if (!domMatches && !dowMatches) return false;
        } else if (dayOfMonthRestricted) {
            if (!domMatches) return false;
        } else if (dayOfWeekRestricted) {
            if (!dowMatches) return false;
        }
        return true;
    }

    /**
     * Finds the next run strictly after {@code from}, or {@code null} if there
     * is none within the next 4 years (e.g. a Feb 29 schedule in a non-leap
     * stretch is still found, since the search covers leap years).
     */
    public ZonedDateTime nextAfter(ZonedDateTime from) {
        ZonedDateTime candidate = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime limit = candidate.plusYears(4);
        while (!candidate.isAfter(limit)) {
            if (matches(candidate)) return candidate;
            candidate = candidate.plusMinutes(1);
        }
        return null;
    }

    public String getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return expression;
    }

    private static boolean isRestricted(String field) {
        return !"*".equals(field.trim());
    }

    private static Set<Integer> parseField(String field, int min, int max,
                                           Map<String, Integer> names, String fieldName) {
        Set<Integer> values = new HashSet<>();
        for (String part : field.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("empty value in cron " + fieldName + " field '" + field + "'");
            }
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = parseValue(part.substring(slash + 1), min, max, names, fieldName);
                if (step <= 0) {
                    throw new IllegalArgumentException("cron step must be positive in '" + part + "'");
                }
            }
            int rangeMin;
            int rangeMax;
            if (range.equals("*") || range.isEmpty()) {
                rangeMin = min;
                rangeMax = max;
            } else if (range.contains("-")) {
                String[] bounds = range.split("-", 2);
                rangeMin = parseValue(bounds[0], min, max, names, fieldName);
                rangeMax = parseValue(bounds[1], min, max, names, fieldName);
                if (rangeMin > rangeMax) {
                    throw new IllegalArgumentException(
                            "cron range '" + range + "' is reversed in " + fieldName + " field");
                }
            } else {
                rangeMin = parseValue(range, min, max, names, fieldName);
                rangeMax = rangeMin;
            }
            for (int value = rangeMin; value <= rangeMax; value += step) {
                // Day-of-week 7 is Sunday, same as 0.
                values.add(max == 7 && value == 7 ? 0 : value);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cron " + fieldName + " field '" + field + "' matches nothing");
        }
        return values;
    }

    private static int parseValue(String raw, int min, int max, Map<String, Integer> names, String fieldName) {
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        Integer named = names != null ? names.get(upper) : null;
        int value;
        if (named != null) {
            value = named;
        } else {
            try {
                value = Integer.parseInt(upper);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "invalid value '" + raw + "' in cron " + fieldName + " field");
            }
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "cron " + fieldName + " value " + value + " is out of range (" + min + "-" + max + ")");
        }
        return value;
    }
}
