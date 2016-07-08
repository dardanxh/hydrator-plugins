/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin.batch.joiner;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.lib.TimePartitionedFileSet;
import co.cask.cdap.api.dataset.table.Put;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.datapipeline.SmartWorkflow;
import co.cask.cdap.etl.api.batch.BatchJoiner;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.cdap.etl.proto.v2.ETLBatchConfig;
import co.cask.cdap.etl.proto.v2.ETLPlugin;
import co.cask.cdap.etl.proto.v2.ETLStage;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.WorkflowManager;
import co.cask.hydrator.plugin.batch.ETLBatchTestBase;
import co.cask.hydrator.plugin.common.Properties;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tests for Joiner.
 */
public class JoinerTestRun extends ETLBatchTestBase {

  @Test
  public void testJoiner() throws Exception {
    /*
     * film         ---------------
     *                              |
     * filmActor    ---------------   joiner ------- sink
     *                              |
     * filmCategory ---------------
     *
     */

    String filmDatasetName = "film-joinertest";
    String filmCategoryDatasetName = "film-category-joinertest";
    String filmActorDatasetName = "film-actor-joinertest";
    String joinedDatasetName = "joined-joinertest";

    Schema filmSchema = Schema.recordOf(
      "film",
      Schema.Field.of("film_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("film_name", Schema.of(Schema.Type.STRING)));

    ETLStage filmStage =
      new ETLStage("film",
                   new ETLPlugin("Table",
                                 BatchSource.PLUGIN_TYPE,
                                 ImmutableMap.of(
                                   Properties.BatchReadableWritable.NAME, filmDatasetName,
                                   Properties.Table.PROPERTY_SCHEMA, filmSchema.toString()),
                                 null));


    Schema filmActorSchema = Schema.recordOf(
      "filmActor",
      Schema.Field.of("film_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("film_name", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("actor_name", Schema.of(Schema.Type.STRING)));

    ETLStage filmActorStage =
      new ETLStage("filmActor",
                   new ETLPlugin("Table",
                                 BatchSource.PLUGIN_TYPE,
                                 ImmutableMap.of(
                                   Properties.BatchReadableWritable.NAME, filmActorDatasetName,
                                   Properties.Table.PROPERTY_SCHEMA, filmActorSchema.toString()),
                                 null));

    Schema filmCategorySchema = Schema.recordOf(
      "filmCategory",
      Schema.Field.of("film_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("film_name", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("category_name", Schema.of(Schema.Type.STRING)));

    ETLStage filmCategoryStage =
      new ETLStage("filmCategory",
                   new ETLPlugin("Table",
                                 BatchSource.PLUGIN_TYPE,
                                 ImmutableMap.of(
                                   Properties.BatchReadableWritable.NAME, filmCategoryDatasetName,
                                   Properties.Table.PROPERTY_SCHEMA, filmCategorySchema.toString()),
                                 null));

    String selectedFields = "film.film_id as film_id, film.film_name as film_name, filmActor.actor_name as " +
      "renamed_actor, filmCategory.category_name as renamed_category";

    ETLStage joinStage =
      new ETLStage("joiner",
                   new ETLPlugin("Joiner",
                                 BatchJoiner.PLUGIN_TYPE,
                                 ImmutableMap.of(
                                   "joinKeys", "film.film_id=filmActor.film_id=filmCategory.film_id&" +
                                     "film.film_name=filmActor.film_name=filmCategory.film_name",
                                   "selectedFields", selectedFields,
                                   "requiredInputs", "film,filmActor,filmCategory"),
                                 null));

    // output schema sorted by input stage names
    Schema outputSchema = Schema.recordOf(
      "joined",
      Schema.Field.of("film_id", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("film_name", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("renamed_actor", Schema.of(Schema.Type.STRING)),
      Schema.Field.of("renamed_category", Schema.of(Schema.Type.STRING)));

    ETLStage joinSinkStage = new ETLStage(
      "sink", new ETLPlugin("TPFSAvro", BatchSink.PLUGIN_TYPE,
                            ImmutableMap.of(Properties.TimePartitionedFileSetDataset.SCHEMA, outputSchema.toString(),
                                            Properties.TimePartitionedFileSetDataset.TPFS_NAME, joinedDatasetName),
                            null));

    ETLBatchConfig config = ETLBatchConfig.builder("* * * * *")
      .addStage(filmStage)
      .addStage(filmActorStage)
      .addStage(filmCategoryStage)
      .addStage(joinStage)
      .addStage(joinSinkStage)
      .addConnection(filmStage.getName(), joinStage.getName())
      .addConnection(filmActorStage.getName(), joinStage.getName())
      .addConnection(filmCategoryStage.getName(), joinStage.getName())
      .addConnection(joinStage.getName(), joinSinkStage.getName())
      .build();
    AppRequest<ETLBatchConfig> request = new AppRequest<>(DATAPIPELINE_ARTIFACT, config);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "joiner-test");
    ApplicationManager appManager = deployApplication(appId, request);

    // ingest data
    ingestToFilmTable(filmDatasetName);
    ingestToFilmActorTable(filmActorDatasetName);
    ingestToFilmCategoryTable(filmCategoryDatasetName);

    // run the pipeline
    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);

    DataSetManager<TimePartitionedFileSet> outputManager = getDataset(joinedDatasetName);
    TimePartitionedFileSet fileSet = outputManager.get();

    // verfiy output
    verifyOutput(outputSchema, fileSet);
  }

  private void ingestToFilmCategoryTable(String filmCategoryDatasetName) throws Exception {
    // 1: 1, matrix, action
    // 2: 1, matrix, thriller
    // 3: 2, equilibrium, action
    DataSetManager<Table> filmCategoryManager = getDataset(filmCategoryDatasetName);
    Table filmCategoryTable = filmCategoryManager.get();
    Put put = new Put(Bytes.toBytes(1));
    put.add("film_id", "1");
    put.add("film_name", "matrix");
    put.add("category_name", "action");
    filmCategoryTable.put(put);

    put = new Put(Bytes.toBytes(2));
    put.add("film_id", "1");
    put.add("film_name", "matrix");
    put.add("category_name", "thriller");
    filmCategoryTable.put(put);

    put = new Put(Bytes.toBytes(3));
    put.add("film_id", "2");
    put.add("film_name", "equilibrium");
    put.add("category_name", "action");
    filmCategoryTable.put(put);
    filmCategoryManager.flush();
  }

  private void ingestToFilmActorTable(String filmActorDatasetName) throws Exception {
    // 1: 1, matrix, alex
    // 2: 1, matrix, bob
    // 3: 2, equilibrium, cathie
    // 4: 3, avatar, samuel
    DataSetManager<Table> filmActorManager = getDataset(filmActorDatasetName);
    Table filmActorTable = filmActorManager.get();
    Put put = new Put(Bytes.toBytes(1));
    put.add("film_id", "1");
    put.add("film_name", "matrix");
    put.add("actor_name", "alex");
    filmActorTable.put(put);

    put = new Put(Bytes.toBytes(2));
    put.add("film_id", "1");
    put.add("film_name", "matrix");
    put.add("actor_name", "bob");
    filmActorTable.put(put);

    put = new Put(Bytes.toBytes(3));
    put.add("film_id", "2");
    put.add("film_name", "equilibrium");
    put.add("actor_name", "cathie");
    filmActorTable.put(put);

    put = new Put(Bytes.toBytes(4));
    put.add("film_id", "3");
    put.add("film_name", "avatar");
    put.add("actor_name", "samuel");
    filmActorTable.put(put);
    filmActorManager.flush();
  }

  private void ingestToFilmTable(String filmDatasetName) throws Exception {
    // write input data
    // 1: 1, matrix
    // 2: 2, equilibrium
    // 3: 3, avatar
    DataSetManager<Table> filmManager = getDataset(filmDatasetName);
    Table filmTable = filmManager.get();
    Put put = new Put(Bytes.toBytes(1));
    put.add("film_id", "1");
    put.add("film_name", "matrix");
    filmTable.put(put);

    put = new Put(Bytes.toBytes(2));
    put.add("film_id", "2");
    put.add("film_name", "equilibrium");
    filmTable.put(put);

    put = new Put(Bytes.toBytes(3));
    put.add("film_id", "3");
    put.add("film_name", "avatar");
    filmTable.put(put);
    filmManager.flush();
  }

  private void verifyOutput(Schema outputSchema, TimePartitionedFileSet fileSet) throws IOException {
    Set<GenericRecord> actual = Sets.newHashSet(readOutput(fileSet, outputSchema));
    Assert.assertEquals(5, actual.size());
    org.apache.avro.Schema avroOutputSchema = new org.apache.avro.Schema.Parser().parse(outputSchema.toString());

    GenericRecord bobRecord1 = new GenericRecordBuilder(avroOutputSchema)
      .set("film_id", "1")
      .set("film_name", "matrix")
      .set("renamed_category", "thriller")
      .set("renamed_actor", "bob")
      .build();

    GenericRecord bobRecord2 = new GenericRecordBuilder(avroOutputSchema)
      .set("film_id", "1")
      .set("film_name", "matrix")
      .set("renamed_category", "action")
      .set("renamed_actor", "bob")
      .build();

    GenericRecord alexRecord1 = new GenericRecordBuilder(avroOutputSchema)
      .set("film_id", "1")
      .set("film_name", "matrix")
      .set("renamed_category", "thriller")
      .set("renamed_actor", "alex")
      .build();

    GenericRecord alexRecord2 = new GenericRecordBuilder(avroOutputSchema)
      .set("film_id", "1")
      .set("film_name", "matrix")
      .set("renamed_category", "action")
      .set("renamed_actor", "alex")
      .build();

    GenericRecord cathieRecord1 = new GenericRecordBuilder(avroOutputSchema)
      .set("film_id", "2")
      .set("film_name", "equilibrium")
      .set("renamed_category", "action")
      .set("renamed_actor", "cathie")
      .build();

    Set<GenericRecord> expected = ImmutableSet.of(bobRecord1, bobRecord2, alexRecord1, alexRecord2, cathieRecord1);
    Assert.assertEquals(expected, actual);
  }
}
