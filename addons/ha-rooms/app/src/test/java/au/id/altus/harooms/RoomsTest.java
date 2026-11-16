package au.id.altus.harooms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoomsTest {
    private static final String STATES = "["
            + "{\"entity_id\":\"light.lounge\",\"state\":\"on\",\"attributes\":{\"friendly_name\":\"Lounge\",\"brightness\":128}},"
            + "{\"entity_id\":\"light.lamp\",\"state\":\"on\",\"attributes\":{\"friendly_name\":\"Lamp\"}},"
            + "{\"entity_id\":\"sensor.lounge_temp\",\"state\":\"19.8\",\"attributes\":{\"friendly_name\":\"Lounge temperature\",\"device_class\":\"temperature\",\"unit_of_measurement\":\"°C\"}},"
            + "{\"entity_id\":\"climate.band\",\"state\":\"cool\",\"attributes\":{\"friendly_name\":\"Band aircon\",\"current_temperature\":24,\"temperature\":23}},"
            + "{\"entity_id\":\"light.band\",\"state\":\"off\",\"attributes\":{\"friendly_name\":\"Band light\"}},"
            + "{\"entity_id\":\"binary_sensor.back_door\",\"state\":\"on\",\"attributes\":{\"friendly_name\":\"Back door\",\"device_class\":\"door\"}},"
            + "{\"entity_id\":\"light.garden\",\"state\":\"off\",\"attributes\":{\"friendly_name\":\"Garden\"}},"
            + "{\"entity_id\":\"light.gone\",\"state\":\"unavailable\",\"attributes\":{\"friendly_name\":\"Gone\"}},"
            + "{\"entity_id\":\"weather.home\",\"state\":\"clear-night\",\"attributes\":{\"temperature\":13}}"
            + "]";

    private static Map<String, String> areas() {
        Map<String, String> a = new HashMap<>();
        a.put("light.lounge", "Living Room");
        a.put("light.lamp", "Living Room");
        a.put("sensor.lounge_temp", "Living Room");
        a.put("climate.band", "Bandroom");
        a.put("light.band", "Bandroom");
        a.put("binary_sensor.back_door", "Backyard");
        a.put("light.garden", "Backyard");
        a.put("light.gone", "Hamish’s Room");
        return a;
    }

    private static List<Rooms.Room> rooms(List<String> show) throws Exception {
        List<Ha.Entity> all = Ha.byArea(Ha.parse(STATES, Collections.emptyList(), show, null, false), areas(), show);
        return Rooms.group(all, areas(), null);
    }

    @Test
    public void groupsRowsIntoRoomsInTheOrderOfShow() throws Exception {
        List<Rooms.Room> r = rooms(Arrays.asList("light.lounge", "light.lamp", "sensor.lounge_temp", "climate.band",
                "light.band", "light.garden", "binary_sensor.back_door", "light.gone"));
        assertEquals(4, r.size());
        assertEquals("Living Room", r.get(0).name);
        assertEquals("Bandroom", r.get(1).name);
        assertEquals("Backyard", r.get(2).name);
        assertEquals(3, r.get(0).entities.size());
    }

    @Test
    public void summariesAndStates() throws Exception {
        List<Rooms.Room> r = rooms(null);
        Map<String, Rooms.Room> by = new HashMap<>();
        for (Rooms.Room x : r) by.put(x.name, x);
        assertEquals("19.8° · 2 lights on", by.get("Living Room").summary());
        assertEquals(Rooms.State.ACTIVE, by.get("Living Room").state());
        assertEquals("Cooling to 23°", by.get("Bandroom").summary());
        assertEquals(Rooms.State.ACTIVE, by.get("Bandroom").state());
        assertEquals("Back door open", by.get("Backyard").summary());
        assertEquals(Rooms.State.WARN, by.get("Backyard").state());
        assertEquals("Offline", by.get("Hamish’s Room").summary());
        assertEquals(Rooms.State.IDLE, by.get("Hamish’s Room").state());
    }

    @Test
    public void indoorTemperatureFromConfigOrFirstRoom() throws Exception {
        List<Ha.Entity> all = Ha.parse(STATES);
        List<Rooms.Room> r = rooms(null);
        assertEquals("19.8", Rooms.indoor(all, r, ""));
        assertEquals("24", Rooms.indoor(all, r, "climate.band"));
        assertNull(Rooms.indoor(all, Collections.emptyList(), "sensor.missing"));
    }

    @Test
    public void iconsFromTheRoomNameUnlessSet() {
        assertEquals("sofa", Rooms.icon("Living Room", null));
        assertEquals("kitchen", Rooms.icon("Dining Room", null));
        assertEquals("bed", Rooms.icon("Callum’s Room", null));
        assertEquals("bed", Rooms.icon("Main Bedroom", null));
        assertEquals("music", Rooms.icon("Bandroom", null));
        assertEquals("tree", Rooms.icon("Backyard", null));
        assertEquals("door", Rooms.icon("Frontyard", null));
        assertEquals("desk", Rooms.icon("Backroom", null));
        assertEquals("stairs", Rooms.icon("Hallway", null));
        assertEquals("house", Rooms.icon("Other", null));
        assertEquals("pool", Rooms.icon("Backyard", Collections.singletonMap("Backyard", "pool")));
        assertEquals("tree", Rooms.icon("Backyard", Collections.singletonMap("Backyard", "nonsense")));
    }

    private static final String COVERS = "["
            + "{\"entity_id\":\"cover.shutter\",\"state\":\"open\",\"attributes\":{\"friendly_name\":\"Shutter\",\"device_class\":\"shutter\",\"current_position\":45,\"supported_features\":15}},"
            + "{\"entity_id\":\"cover.curtain\",\"state\":\"closed\",\"attributes\":{\"friendly_name\":\"Curtain\",\"supported_features\":3}},"
            + "{\"entity_id\":\"cover.moving\",\"state\":\"closing\",\"attributes\":{\"friendly_name\":\"Moving\",\"supported_features\":11}},"
            + "{\"entity_id\":\"cover.damper\",\"state\":\"open\",\"attributes\":{\"friendly_name\":\"Damper\",\"device_class\":\"damper\",\"current_position\":100,\"supported_features\":7}},"
            + "{\"entity_id\":\"cover.garage\",\"state\":\"open\",\"attributes\":{\"friendly_name\":\"Garage door\",\"device_class\":\"garage\",\"supported_features\":3}}"
            + "]";

    private static Ha.Entity find(List<Ha.Entity> all, String id) {
        for (Ha.Entity e : all) if (e.id.equals(id)) return e;
        return null;
    }

    @Test
    public void coversOpenCloseStopAndMove() throws Exception {
        List<Ha.Entity> all = Ha.parse(COVERS);
        Ha.Entity shutter = find(all, "cover.shutter"), curtain = find(all, "cover.curtain"), moving = find(all, "cover.moving");
        assertEquals(Ha.Kind.COVER, shutter.kind);
        assertEquals("Open · 45 %", shutter.value());
        assertEquals("Closed", curtain.value());
        assertEquals("/api/services/cover/close_cover", Ha.press(shutter).path);
        assertEquals("/api/services/cover/open_cover", Ha.press(curtain).path);
        assertEquals("/api/services/cover/stop_cover", Ha.press(moving).path);
        assertEquals("{\"entity_id\":\"cover.shutter\",\"position\":50}", Ha.step(shutter, 1).body);
        assertEquals("{\"entity_id\":\"cover.shutter\",\"position\":30}", Ha.step(shutter, -1).body);
        assertNull("no position: no steps", Ha.step(curtain, 1));
        assertEquals("curtain", Rooms.coverWord(curtain));
        assertEquals("shutter", Rooms.coverWord(shutter));
    }

    @Test
    public void openCoversInTheRoomLineAndAGarageWarns() throws Exception {
        Map<String, String> a = new HashMap<>();
        a.put("cover.shutter", "Backroom");
        a.put("cover.curtain", "Backroom");
        a.put("cover.damper", "Backroom");
        a.put("cover.garage", "Garage");
        List<Ha.Entity> all = Ha.byArea(Ha.parse(COVERS), a, null);
        Map<String, Rooms.Room> by = new HashMap<>();
        for (Rooms.Room x : Rooms.group(all, a, null)) by.put(x.name, x);
        assertEquals("1 shutter open", by.get("Backroom").summary());
        assertEquals(Rooms.State.ACTIVE, by.get("Backroom").state());
        assertEquals("Garage door open", by.get("Garage").summary());
        assertEquals(Rooms.State.WARN, by.get("Garage").state());
    }
}
