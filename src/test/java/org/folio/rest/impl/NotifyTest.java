package org.folio.rest.impl;

import org.junit.Test;
import static org.junit.Assert.*;
import com.jayway.restassured.RestAssured;
import static com.jayway.restassured.RestAssured.given;
import com.jayway.restassured.response.Header;
import static org.hamcrest.Matchers.containsString;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.runner.RunWith;

import org.folio.rest.RestVerticle;
import org.folio.rest.persist.PostgresClient;
import org.junit.After;

/**
 * Interface test for mod-notes. Tests the API with restAssured, directly
 * against the module - without any Okapi in the picture. Since we run with an
 * embedded postgres, we always start with an empty database, and can safely
 * leave test data in it.
 *
 * @author heikki
 */
@RunWith(VertxUnitRunner.class)
public class NotifyTest {

  private final Logger logger = LoggerFactory.getLogger("okapi");
  private final int port = Integer.parseInt(System.getProperty("port", "8081"));
  private static final String LS = System.lineSeparator();
  private final Header TEN = new Header("X-Okapi-Tenant", "testlib");
  private final Header USER7 = new Header("X-Okapi-User-Id",
    "77777777-7777-7777-7777-777777777777");
  private final Header USER8 = new Header("X-Okapi-User-Id",
    "88888888-8888-8888-8888-888888888888");
  private final Header USER9 = new Header("X-Okapi-User-Id",
    "99999999-9999-9999-9999-999999999999");

  private final Header JSON = new Header("Content-Type", "application/json");
  private String moduleName = "mod-notify";
  private String moduleVersion = "0.2.0-SNAPSHOT";
  private String moduleId = moduleName + "-" + moduleVersion;
  Vertx vertx;
  Async async;

  @Before
  public void setUp(TestContext context) {
    vertx = Vertx.vertx();
    logger.info("notifyTest: Setup starting");

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      e.printStackTrace();
      context.fail(e);
      return;
    }

    JsonObject conf = new JsonObject()
      .put("http.port", port);

    logger.info("notifyTest: Deploying "
      + RestVerticle.class.getName() + " "
      + Json.encode(conf));
    DeploymentOptions opt = new DeploymentOptions()
      .setConfig(conf);
    vertx.deployVerticle(RestVerticle.class.getName(),
      opt, context.asyncAssertSuccess());
    RestAssured.port = port;
    logger.info("notifyTest: setup done. Using port " + port);
  }

  @After
  public void tearDown(TestContext context) {
    logger.info("Cleaning up after ModuleTest");
    async = context.async();
    vertx.close(context.asyncAssertSuccess(res -> {
      PostgresClient.stopEmbeddedPostgres();
      async.complete();
    }));
  }

  /**
   * All the tests. In one long function, because starting up the embedded
   * postgres takes so long and fills the log.
   *
   * @param context
   */
  @Test
  public void tests(TestContext context) {
    async = context.async();
    logger.info("notifyTest starting");

    // Simple GET request to see the module is running and we can talk to it.
    given()
      .get("/admin/health")
      .then()
      .log().all()
      .statusCode(200);

    // Simple GET request without a tenant
    given()
      .get("/notify")
      .then()
      .log().all()
      .statusCode(400)
      .body(containsString("Tenant"));

    // Simple GET request with a tenant, but before
    // we have invoked the tenant interface, so the
    // call will fail (with lots of traces in the log)
    given()
      .header(TEN)
      .get("/notify")
      .then()
      .log().all()
      .statusCode(400)
      .body(containsString("\"testlib_mod_notify.notify_data\" does not exist"));

    // Call the tenant interface to initialize the database
    String tenants = "{\"module_to\":\"" + moduleId + "\"}";
    logger.info("About to call the tenant interface " + tenants);
    given()
      .header(TEN).header(JSON)
      .body(tenants)
      .post("/_/tenant")
      .then()
      .log().ifError()
      .statusCode(201);

    // Empty list of notes
    given()
      .header(TEN)
      .get("/notify")
      .then()
      .log().ifError()
      .statusCode(200)
      .body(containsString("\"notifications\" : [ ]"));

    // Post some malformed notes
    String bad1 = "This is not json";
    given()
      .header(TEN) // no content-type header
      .body(bad1)
      .post("/notify")
      .then()
      .statusCode(400)
      .body(containsString("Content-type"));

    given()
      .header(TEN).header(JSON)
      .body(bad1)
      .post("/notify")
      .then()
      .statusCode(400)
      .body(containsString("Json content error"));

    String note1 = "{"
      + "\"id\" : \"11111111-1111-1111-1111-111111111111\"," + LS
      + "\"recipientId\" : \"77777777-7777-7777-7777-777777777777\"," + LS
      + "\"link\" : \"users/1234\"," + LS
      + "\"text\" : \"First notification\"}" + LS;

    String bad2 = note1.replaceFirst("}", ")"); // make it invalid json
    given()
      .header(TEN).header(JSON)
      .body(bad2)
      .post("/notify")
      .then()
      .statusCode(400)
      .body(containsString("Json content error"));

    String bad3 = note1.replaceFirst("text", "badFieldName");
    given()
      .header(TEN).header(JSON)
      .body(bad3)
      .post("/notify")
      .then()
      .statusCode(422)
      .body(containsString("may not be null"))
      .body(containsString("\"text\","));

    String bad4 = note1.replaceAll("-1111-", "-2-");  // make bad UUID
    given()
      .header(TEN).header(JSON)
      .body(bad4)
      .post("/notify")
      .then()
      .log().all()
      .statusCode(400)
      .body(containsString("invalid input syntax for uuid"));

    // Post a good note
    given()
      .header(TEN).header(USER9).header(JSON)
      .body(note1)
      .post("/notify")
      .then()
      .log().ifError()
      .statusCode(201);

    // Fetch the notification in various ways
    given()
      .header(TEN)
      .get("/notify")
      .then()
      .log().all()
      .statusCode(200)
      .body(containsString("First notification"))
      .body(containsString("-9999-")) // CreatedBy userid in metadata
      .body(containsString("\"totalRecords\" : 1"));

    given()
      .header(TEN)
      .get("/notify/11111111-1111-1111-1111-111111111111")
      .then()
      .statusCode(200)
      .body(containsString("First notification"));

    given()
      .header(TEN)
      .get("/notify/777")
      .then()
      .log().all()
      .statusCode(400);

    given()
      .header(TEN)
      .get("/notify?query=text=fiRST")
      .then()
      .statusCode(200)
      .body(containsString("First notification"));

    String note2 = "{"
      + "\"id\" : \"22222222-2222-2222-2222-222222222222\"," + LS
      + "\"recipientId\" : \"77777777-7777-7777-7777-777777777777\"," + LS
      + "\"link\" : \"things/23456\"," + LS
      + "\"seen\" : false,"
      + "\"text\" : \"Notification on a thing\"}" + LS;

    // Post another note
    given()
      .header(TEN).header(USER8).header(JSON)
      .body(note2)
      .post("/notify")
      .then()
      .log().ifError()
      .statusCode(201);

    // Get both notes a few different ways
    given()
      .header(TEN)
      .get("/notify?query=text=notification")
      .then()
      .log().all()
      .statusCode(200)
      .body(containsString("First notification"))
      .body(containsString("things/23456"));

    // Check seen
    given()
      .header(TEN)
      .get("/notify?query=seen=false")
      .then()
      .log().all()
      .statusCode(200)
      .body(containsString("First notification"));

    given()
      .header(TEN)
      .get("/notify?query=seen=true")
      .then()
      .log().all()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 0"));

    // Update a note
    String updated1 = "{"
      + "\"id\" : \"11111111-1111-1111-1111-111111111111\"," + LS
      + "\"recipientId\" : \"77777777-7777-7777-7777-777777777777\"," + LS
      + "\"link\" : \"users/1234\"," + LS
      + "\"seen\" : true," + LS
      + "\"text\" : \"First notification with a comment\"}" + LS;

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/22222222-2222-2222-2222-222222222222") // wrong one
      .then()
      .log().ifError()
      .statusCode(422)
      .body(containsString("Can not change the id"));

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/55555555-5555-5555-5555-555555555555") // bad one
      .then()
      .log().ifError()
      .statusCode(422)
      .body(containsString("Can not change the id"));

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/11111111-222-1111-2-111111111111") // invalid UUID
      .then()
      .log().ifError()
      .statusCode(422);

    given()
      .header(TEN).header(USER8).header(JSON)
      .body(updated1)
      .put("/notify/11111111-1111-1111-1111-111111111111")
      .then()
      .log().ifError()
      .statusCode(204);

    given()
      .header(TEN)
      .get("/notify/11111111-1111-1111-1111-111111111111")
      .then()
      .log().all()
      .statusCode(200)
      .body(containsString("\"seen\" : true"))
      .body(containsString("with a comment"))
      .body(containsString("-8888-"));   // updated by

    // _self
    given()
      .header(TEN).header(USER7)
      .get("/notify/_self")
      .then()
      .statusCode(200)
      .body(containsString("\"totalRecords\" : 2")) // both match recipient 7
      .body(containsString("with a comment"));

    given()
      .header(TEN).header(USER8)
      .get("/notify/_self")
      .then()
      .log().all()
      .body(containsString("\"totalRecords\" : 0")); // none match 8

    // Failed deletes
    given()
      .header(TEN)
      .delete("/notify/11111111-3-1111-333-111111111111") // Bad UUID
      .then()
      .log().all()
      .statusCode(400);

    given()
      .header(TEN)
      .delete("/notify/11111111-2222-3333-4444-555555555555") // not found
      .then()
      .statusCode(404);

    // delete them
    given()
      .header(TEN)
      .delete("/notify/11111111-1111-1111-1111-111111111111")
      .then()
      .statusCode(204);

    given()
      .header(TEN)
      .delete("/notify/11111111-1111-1111-1111-111111111111") // no longer there
      .then()
      .statusCode(404);

    given()
      .header(TEN)
      .delete("/notify/22222222-2222-2222-2222-222222222222")
      .then()
      .statusCode(204);

    given()
      .header(TEN)
      .get("/notify")
      .then()
      .log().ifError()
      .statusCode(200)
      .body(containsString("\"notifications\" : [ ]"));

    // All done
    logger.info("notifyTest done");
    async.complete();
  }

}
