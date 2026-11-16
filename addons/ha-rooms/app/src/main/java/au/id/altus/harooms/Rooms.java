package au.id.altus.harooms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The list rows gathered into rooms (Home Assistant's areas), each with what its tile says: a status line, and
 * whether something there wants attention. No Android types, so the JVM tests cover it.
 */
final class Rooms {
    /** How a room's tile is drawn: nothing on, something on, or something to look at (a door open, a lock open). */
    enum State { IDLE, ACTIVE, WARN }

    static final class Room {
        final String name;
        final String icon;
        final List<Ha.Entity> entities;

        Room(String name, String icon, List<Ha.Entity> entities) {
            this.name = name;
            this.icon = icon;
            this.entities = Collections.unmodifiableList(entities);
        }

        /** The first thing that wants attention, e.g. "Back Door open"; null when there is none. */
        String warning() {
            for (Ha.Entity e : entities) {
                if (!e.available()) continue;
                if (e.kind == Ha.Kind.OPENING && "on".equals(e.state)) return e.name + " open";
                if (e.kind == Ha.Kind.COVER && gate(e) && !"closed".equals(e.state)) return e.name + " " + e.state;
                if (e.kind == Ha.Kind.LOCK && !"locked".equals(e.state)) return e.name + " " + e.value().toLowerCase(Locale.ROOT);
            }
            return null;
        }

        State state() {
            if (warning() != null) return State.WARN;
            for (Ha.Entity e : entities) {
                if (!e.available()) continue;
                if (e.kind == Ha.Kind.TOGGLE && "on".equals(e.state)) return State.ACTIVE;
                if (e.kind == Ha.Kind.CLIMATE && !"off".equals(e.state)) return State.ACTIVE;
                if (e.kind == Ha.Kind.COVER && !damper(e) && !"closed".equals(e.state)) return State.ACTIVE;
            }
            return State.IDLE;
        }

        /** The line under the tile: "19.8° · 2 lights on · Cooling to 23°", the warning first when there is one. */
        String summary() {
            List<String> bits = new ArrayList<>();
            String warn = warning();
            if (warn != null) bits.add(warn);
            Ha.Entity temp = temperature();
            if (temp != null) bits.add(temp.value().replace("°C", "°").replace("°F", "°"));
            int lights = 0, others = 0, covers = 0;
            String coverWord = null;
            String climate = null;
            boolean any = false;
            for (Ha.Entity e : entities) {
                if (!e.available()) continue;
                any = true;
                if (e.kind == Ha.Kind.TOGGLE && "on".equals(e.state)) {
                    if (e.domain().equals("light")) lights++;
                    else others++;
                } else if (e.kind == Ha.Kind.COVER && !gate(e) && !damper(e) && !"closed".equals(e.state)) {
                    covers++;
                    String w = coverWord(e);
                    coverWord = coverWord == null || coverWord.equals(w) ? w : "blind";
                } else if (e.kind == Ha.Kind.CLIMATE && climate == null && !"off".equals(e.state)) {
                    climate = climateLine(e);
                }
            }
            if (lights > 0) bits.add(lights + (lights == 1 ? " light on" : " lights on"));
            if (others > 0) bits.add(others + " on");
            if (covers > 0) bits.add(covers + " " + coverWord + (covers == 1 ? "" : "s") + " open");
            if (climate != null) bits.add(climate);
            if (bits.isEmpty()) return any ? "All off" : "Offline";
            if (temp != null && bits.size() == 1 && warn == null) bits.add("All off");
            return String.join(" · ", bits);
        }

        /** The room's temperature: its first temperature sensor, else its thermostat's reading. */
        Ha.Entity temperature() {
            for (Ha.Entity e : entities) {
                if (e.kind == Ha.Kind.SENSOR && e.available() && e.unit.startsWith("°")) return e;
            }
            return null;
        }
    }

    private Rooms() {
    }

    /** A garage door or gate left open is a warning, like a door; blinds and shutters are not. */
    static boolean gate(Ha.Entity e) {
        return e.unit.equals("garage") || e.unit.equals("gate") || e.unit.equals("door");
    }

    /** An aircon zone damper: open only means air is flowing, so it is not counted or lit. */
    static boolean damper(Ha.Entity e) {
        return e.unit.equals("damper");
    }

    /** "shutter", "curtain"...: the word for a cover's device class, or its name; "blind" otherwise. */
    static String coverWord(Ha.Entity e) {
        switch (e.unit) {
            case "shutter":
            case "curtain":
            case "awning":
            case "shade":
            case "window":
                return e.unit;
            default:
                // No device class: the name may still say what it is ("Hamish's Shutters").
                String n = e.name.toLowerCase(Locale.ROOT);
                for (String w : new String[]{"shutter", "curtain", "awning", "shade"}) if (n.contains(w)) return w;
                return "blind";
        }
    }

    /**
     * Rooms in the order {@link Ha#byArea} puts them, from rows already sorted by it. Rows in no area make a room
     * called {@link Ha#NO_AREA}, last.
     */
    static List<Room> group(List<Ha.Entity> sorted, Map<String, String> areas, Map<String, String> icons) {
        List<Room> out = new ArrayList<>();
        String current = null;
        List<Ha.Entity> members = null;
        for (Ha.Entity e : sorted) {
            if (!Ha.inList(e)) continue;
            String area = Ha.area(areas, e.id);
            if (!area.equals(current)) {
                if (members != null) out.add(new Room(current, icon(current, icons), members));
                current = area;
                members = new ArrayList<>();
            }
            members.add(e);
        }
        if (members != null) out.add(new Room(current, icon(current, icons), members));
        return out;
    }

    /** Icon names, each a drawable room_<name>. */
    static final List<String> ICONS = Collections.unmodifiableList(java.util.Arrays.asList(
            "sofa", "kitchen", "bed", "desk", "stairs", "music", "tree", "garage", "pool", "door", "house"));

    /** The icon a room's name suggests, unless the config names one for it. */
    static String icon(String room, Map<String, String> overrides) {
        String set = overrides == null ? null : overrides.get(room);
        if (set != null && ICONS.contains(set)) return set;
        String n = room.toLowerCase(Locale.ROOT);
        if (has(n, "living", "lounge", "family", "tv room", "rumpus")) return "sofa";
        if (has(n, "kitchen", "dining", "pantry")) return "kitchen";
        if (has(n, "band", "music", "studio")) return "music";
        if (has(n, "garage", "carport", "shed")) return "garage";
        if (has(n, "pool", "spa")) return "pool";
        if (has(n, "front", "porch", "entry", "entrance")) return "door";
        if (has(n, "yard", "garden", "outside", "outdoor", "patio", "pergola", "deck", "balcony")) return "tree";
        if (has(n, "bed", "room of", "nursery", "kids") || n.endsWith("’s room") || n.endsWith("'s room")) return "bed";
        if (has(n, "office", "study", "back room", "backroom", "den", "work")) return "desk";
        if (has(n, "hall", "stair", "landing", "corridor")) return "stairs";
        return "house";
    }

    private static boolean has(String name, String... words) {
        for (String w : words) if (name.contains(w)) return true;
        return false;
    }

    /**
     * The big number at the top: the entity the config names, else the first room's temperature, else the first
     * thermostat's reading. Null when there is none.
     */
    static String indoor(List<Ha.Entity> all, List<Room> rooms, String id) {
        if (id != null && !id.isEmpty()) {
            for (Ha.Entity e : all) {
                if (!e.id.equals(id) || !e.available()) continue;
                if (e.kind == Ha.Kind.CLIMATE) return Double.isNaN(e.current) ? null : Ha.number(e.current);
                try {
                    return Ha.number(Double.parseDouble(e.state));
                } catch (NumberFormatException x) {
                    return null;
                }
            }
        }
        for (Room r : rooms) {
            Ha.Entity t = r.temperature();
            if (t == null) continue;
            try {
                return Ha.number(Double.parseDouble(t.state));
            } catch (NumberFormatException ignored) {
                // Not a number: try the next room.
            }
        }
        for (Room r : rooms) {
            for (Ha.Entity e : r.entities) {
                if (e.kind == Ha.Kind.CLIMATE && e.available() && !Double.isNaN(e.current)) return Ha.number(e.current);
            }
        }
        return null;
    }

    /** "Cooling to 23°", "Heating to 21°", or "Aircon 23°" for other modes. */
    static String climateLine(Ha.Entity e) {
        String set = Double.isNaN(e.target) ? "" : Ha.number(e.target) + "°";
        switch (e.state) {
            case "cool": return set.isEmpty() ? "Cooling" : "Cooling to " + set;
            case "heat": return set.isEmpty() ? "Heating" : "Heating to " + set;
            case "dry": return "Drying";
            case "fan_only": return "Fan";
            default: return set.isEmpty() ? "Climate on" : "Climate " + set;
        }
    }
}
