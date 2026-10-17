package app.driftdictation;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class McpTest {
  @Test
  public void readsTheAnswerFromAnEventStream() throws Exception {
    String stream =
        "event: message\n"
            + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
            + "event: message\r\n"
            + "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\r\n\r\n";
    JSONObject event = Mcp.Connection.fromStream(stream, 2);
    assertNotNull(event);
    assertTrue(event.getJSONObject("result").getBoolean("ok"));
    // As bb sends it: one event, ending in a blank line.
    assertEquals(
        1,
        Mcp.Connection.fromStream("event: message\ndata: {\"id\":1,\"result\":{}}\n\n", 1).getInt("id"));
  }
}
