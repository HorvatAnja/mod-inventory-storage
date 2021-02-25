package org.folio.rest.api;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.StorageHelperTest;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.support.HttpClient;
import org.folio.rest.support.Response;
import org.folio.rest.support.ResponseHandler;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.rest.unit.ItemDamagedStatusAPIUnitTest;
import org.folio.services.CallNumberUtils;
import org.folio.services.CallNumberUtilsTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import com.consol.citrus.kafka.embedded.EmbeddedKafkaServer;
import com.consol.citrus.kafka.embedded.EmbeddedKafkaServerBuilder;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

@RunWith(Suite.class)
@Suite.SuiteClasses({
  CallNumberUtilsTest.class,
  InstanceStorageTest.class,
  RecordBulkTest.class,
  HoldingsStorageTest.class,
  ItemStorageTest.class,
  HoldingsTypeTest.class,
  LoanTypeTest.class,
  MaterialTypeTest.class,
  ContributorTypesTest.class,
  ShelfLocationsTest.class,
  LocationUnitTest.class,
  LocationsTest.class,
  ServicePointTest.class,
  ServicePointsUserTest.class,
  StorageHelperTest.class,
  InstanceRelationshipsTest.class,
  ReferenceTablesTest.class,
  ItemDamagedStatusAPITest.class,
  ItemDamagedStatusAPIUnitTest.class,
  ItemEffectiveLocationTest.class,
  SampleDataTest.class,
  HridSettingsStorageTest.class,
  HridSettingsStorageParameterizedTest.class,
  ItemCopyNumberMigrationScriptTest.class,
  ItemEffectiveCallNumberComponentsTest.class,
  ItemEffectiveCallNumberDataUpgradeTest.class,
  ModesOfIssuanceMigrationScriptTest.class,
  PrecedingSucceedingTitleTest.class,
  HoldingsCallNumberNormalizedTest.class,
  ItemCallNumberNormalizedTest.class,
  AbstractInstanceRecordsAPITest.class,
  OaiPmhViewTest.class,
  InventoryHierarchyViewTest.class,
  HoldingsSourceTest.class,
  InstanceDomainEventTest.class,
  KafkaTenantInitTest.class
})
public class StorageTestSuite {
  public static final String TENANT_ID = "test_tenant";
  private static Logger logger = LogManager.getLogger();
  private static Vertx vertx;
  private static int port;

  private static final EmbeddedKafkaServer kafka = new EmbeddedKafkaServerBuilder()
    .kafkaServerPort(9092).build();

  private StorageTestSuite() {
    throw new UnsupportedOperationException("Cannot instantiate utility class.");
  }

  public static URL storageUrl(String path) {
    try {
      return new URL("http", "localhost", port, path);
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static Vertx getVertx() {
    return vertx;
  }

  @BeforeClass
  public static void before()
    throws Exception {

    logger.info("starting @BeforeClass before()");

    // tests expect English error messages only, no Danish/German/...
    Locale.setDefault(Locale.US);

    vertx = Vertx.vertx();

    String useExternalDatabase = System.getProperty(
      "org.folio.inventory.storage.test.database",
      "embedded");

    switch (useExternalDatabase) {
      case "environment":
        System.out.println("Using environment settings");
        break;

      case "external":
        String postgresConfigPath = System.getProperty(
          "org.folio.inventory.storage.test.config",
          "/postgres-conf-local.json");

        PostgresClient.setConfigFilePath(postgresConfigPath);
        break;
      case "embedded":
        PostgresClient.setIsEmbedded(true);
        PostgresClient.getInstance(vertx).startEmbeddedPostgres();
        break;
      default:
        String message = "No understood database choice made." +
          "Please set org.folio.inventory.storage.test.config" +
          "to 'external', 'environment' or 'embedded'";

        throw new Exception(message);
    }

    logger.info("starting RestVerticle");

    port = NetworkUtils.nextFreePort();
    DeploymentOptions options = new DeploymentOptions();
    options.setConfig(new JsonObject().put("http.port", port));
    startVerticle(options);

    logger.info("preparing tenant");

    kafka.start();
    prepareTenant(TENANT_ID, false);

    logger.info("finished @BeforeClass before()");
  }

  @AfterClass
  public static void after()
    throws InterruptedException,
    ExecutionException,
    TimeoutException {

    removeTenant(TENANT_ID);
    vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    PostgresClient.stopEmbeddedPostgres();
    kafka.stop();
  }

  static void deleteAll(URL rootUrl) {
    HttpClient client = new HttpClient(getVertx());

    CompletableFuture<Response> deleteAllFinished = new CompletableFuture<>();

    try {
      client.delete(rootUrl, TENANT_ID,
        ResponseHandler.any(deleteAllFinished));

      Response response = TestBase.get(deleteAllFinished);

      if (response.getStatusCode() != 204) {
        Assert.fail("Delete all preparation failed: " +
          response.getBody());
      }
    } catch (Exception e) {
      throw new RuntimeException("WARNING!!!!! Unable to delete all: " + e.getMessage(), e);
    }
  }

  static void checkForMismatchedIDs(String table) {
    try {
      RowSet<Row> results = getRecordsWithUnmatchedIds(
        TENANT_ID, table);

      Integer mismatchedRowCount = results.rowCount();

      assertThat(mismatchedRowCount, is(0));
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      throw new RuntimeException("WARNING!!!!! Unable to determine mismatched ID rows" + e.getMessage(), e);
    }
  }

  protected static Boolean deleteAll(String tenantId, String tableName) {
    CompletableFuture<Boolean> cf = new CompletableFuture<>();

    try {
      PostgresClient postgresClient = PostgresClient.getInstance(getVertx(), tenantId);

      Promise<RowSet<Row>> promise = Promise.promise();
      String sql = String.format("DELETE FROM %s_%s.%s", tenantId, "mod_inventory_storage", tableName);
      postgresClient.execute(sql, promise);

      promise.future()
        .map(deleteResult -> cf.complete(deleteResult.rowCount() >= 0))
        .otherwise(error -> cf.complete(false));

      return TestBase.get(cf);
    } catch (Exception e) {
      throw new RuntimeException("WARNING!!!!! Unable to delete all: " + e.getMessage(), e);
    }
  }

  private static RowSet<Row> getRecordsWithUnmatchedIds(String tenantId,
                                                      String tableName)
    throws InterruptedException, ExecutionException, TimeoutException {

    PostgresClient dbClient = PostgresClient.getInstance(
      getVertx(), tenantId);

    CompletableFuture<RowSet<Row>> selectCompleted = new CompletableFuture<>();

    String sql = String.format("SELECT null FROM %s_%s.%s" +
        " WHERE CAST(id AS VARCHAR(50)) != jsonb->>'id'",
      tenantId, "mod_inventory_storage", tableName);

    dbClient.select(sql, result -> {
      if (result.succeeded()) {
        selectCompleted.complete(result.result());
      } else {
        selectCompleted.completeExceptionally(result.cause());
      }
    });

    return TestBase.get(selectCompleted);
  }

  private static void startVerticle(DeploymentOptions options)
    throws InterruptedException, ExecutionException, TimeoutException {

    vertx.deployVerticle(RestVerticle.class, options)
    .toCompletionStage()
    .toCompletableFuture()
    .get(20, TimeUnit.SECONDS);
  }

  static void prepareTenant(String tenantId, String moduleFrom, String moduleTo, boolean loadSample)
    throws InterruptedException,
    ExecutionException,
    TimeoutException {

    JsonArray ar = new JsonArray();
    ar.add(new JsonObject().put("key", "loadReference").put("value", "true"));
    ar.add(new JsonObject().put("key", "loadSample").put("value", Boolean.toString(loadSample)));

    JsonObject jo = new JsonObject();
    jo.put("parameters", ar);
    if (moduleFrom != null) {
      jo.put("module_from", moduleFrom);
    }
    jo.put("module_to", moduleTo);
    tenantOp(tenantId, jo);
  }

  static void prepareTenant(String tenantId, boolean loadSample)
      throws InterruptedException,
      ExecutionException,
      TimeoutException {
    prepareTenant(tenantId, null, "mod-inventory-storage-1.0.0", loadSample);
  }

  static void removeTenant(String tenantId) throws InterruptedException, ExecutionException, TimeoutException {

    JsonObject jo = new JsonObject();
    jo.put("purge", Boolean.TRUE);

    tenantOp(tenantId, jo);
  }

  public static void tenantOp(String tenantId, JsonObject job) throws InterruptedException, ExecutionException, TimeoutException {
    CompletableFuture<Response> tenantPrepared = new CompletableFuture<>();

    HttpClient client = new HttpClient(vertx);
    client.post(storageUrl("/_/tenant"), job, tenantId,
        ResponseHandler.any(tenantPrepared));

    Response response = tenantPrepared.get(60, TimeUnit.SECONDS);

    String failureMessage = String.format("Tenant post failed: %s: %s",
        response.getStatusCode(), response.getBody());

    // wait if not complete ...
    if (response.getStatusCode() == 201) {
      String id = response.getJson().getString("id");

      tenantPrepared = new CompletableFuture<>();
      client.get(storageUrl("/_/tenant/" + id + "?wait=60000"), tenantId,
          ResponseHandler.any(tenantPrepared));
      response = tenantPrepared.get(60, TimeUnit.SECONDS);

      failureMessage = String.format("Tenant get failed: %s: %s",
          response.getStatusCode(), response.getBody());

      assertThat(failureMessage, response.getStatusCode(), is(200));
    } else {
      assertThat(failureMessage, response.getStatusCode(), is(204));
    }
  }
}
