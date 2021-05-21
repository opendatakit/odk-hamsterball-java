package org.benetech.controller.ajax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.benetech.ajax.SurveyQuestion;
import org.benetech.client.OdkClient;
import org.benetech.client.OdkClientFactory;
import org.benetech.model.display.OdkTablesFileManifestEntryDisplay;
import org.benetech.model.formDef.FormDef;
import org.benetech.model.formDef.Prompt;
import org.benetech.util.OdkClientUtils;
import org.opendatakit.aggregate.odktables.rest.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
public class TablesControllerAjax {

  private static Log logger = LogFactory.getLog(TablesControllerAjax.class);

  private static final String FORMS_JSON_FILENAME = "formDef.json";

  @Autowired
  OdkClientFactory odkClientFactory;

  @GetMapping(value = "/tables/{tableId}/rows/{rowId}", produces = "application/json;charset=UTF-8")
  public ResponseEntity<?> getRowDetail(@PathVariable("tableId") String tableId,
                                        @PathVariable(name = "rowId") String rowId, Model model) {

    OdkClient odkClient = odkClientFactory.getOdkClient();
    TableResource tableResource = odkClient.getTableResource(tableId);
    RowResource rowResource = odkClient.getSingleRow(tableId, tableResource.getSchemaETag(), rowId);


    return ResponseEntity.ok(rowResource);
  }

  @GetMapping(value = "/tables/{tableId}/rows/{rowId}/map", produces = "application/json;charset=UTF-8")
  public ResponseEntity<?> getRowDetailMap(@PathVariable("tableId") String tableId,
                                           @PathVariable(name = "rowId") String rowId, Model model) {

    OdkClient odkClient = odkClientFactory.getOdkClient();
    TableResource tableResource = odkClient.getTableResource(tableId);
    RowResource rowResource = odkClient.getSingleRow(tableId, tableResource.getSchemaETag(), rowId);
    Map<String, String> mappedRowValues = new HashMap<String, String>();
    for (DataKeyValue value : rowResource.getValues()) {
      if (value.column.toLowerCase().endsWith("_contentType".toLowerCase())) {
        // skip
      } else if (value.column.toLowerCase().endsWith("_urifragment")) {
        String origColumnName =
                value.column.substring(0, value.column.length() - "_uriFragment".length());
        mappedRowValues.put(origColumnName, value.value);
      } else {
        mappedRowValues.put(value.column, value.value);
      }
    }

    return ResponseEntity.ok(mappedRowValues);
  }

  @GetMapping(value = "/tables/{tableId}/rows/{rowId}/attachments", produces = "application/json;charset=UTF-8")
  public ResponseEntity<?> getRowAttachments(@PathVariable("tableId") String tableId,
                                             @PathVariable(name = "rowId") String rowId, Model model) {

    OdkClient odkClient = odkClientFactory.getOdkClient();
    TableResource tableResource = odkClient.getTableResource(tableId);
    OdkTablesFileManifest manifest =
            odkClient.getSingleRowAttachments(tableId, tableResource.getSchemaETag(), rowId);

    Map<String, OdkTablesFileManifestEntry> entryMap =
            new HashMap<String, OdkTablesFileManifestEntry>();
    for (OdkTablesFileManifestEntry entry : manifest.getFiles()) {
      entryMap.put(entry.filename, new OdkTablesFileManifestEntryDisplay(entry));
    }

    return ResponseEntity.ok(entryMap);
  }

  @GetMapping(value = "/tables/{tableId}/questions", produces = "application/json;charset=UTF-8")
  public ResponseEntity<?> getFormJson(@PathVariable("tableId") String tableId) {
    OdkClient odkClient = odkClientFactory.getOdkClient();

    OdkTablesFileManifest manifest = odkClient.getTableManifest(tableId);
    OdkTablesFileManifestEntry formDefEntry = null;
    for (OdkTablesFileManifestEntry entry : manifest.getFiles()) {
      if (entry.filename != null
              && entry.filename.toLowerCase().endsWith(FORMS_JSON_FILENAME.toLowerCase())) {
        formDefEntry = entry;
        break;
      }
    }
    // assume that formDef exists
    String jsonFormDefinition = odkClient.getFormDefinition(formDefEntry.downloadUrl);
    Map<String, SurveyQuestion> surveyQuestionMap = new HashMap<>();
    try {
      FormDef rootNode = new ObjectMapper().readValue(jsonFormDefinition, FormDef.class);
      surveyQuestionMap = getSurveyQuestionMap(rootNode);
    } catch (JsonProcessingException e) {
      logger.error(e);
    } catch (IOException e) {
      logger.error(e);
    }

    return ResponseEntity.ok(surveyQuestionMap);
  }

  @GetMapping(value = "/tables/{tableId}/export/{format}/showDeleted/{showDeleted}")
  public ResponseEntity<?> getTable(@PathVariable("tableId") String tableId,
                                    @PathVariable("format") String format,
                                    @PathVariable("showDeleted") boolean showDeleted) {
    if (!format.equalsIgnoreCase("JSON") && !format.equalsIgnoreCase("CSV")) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Valid formats are JSON and CSV");
    }

    OdkClient odkClient = odkClientFactory.getOdkClient();

    TableResource tableResource = odkClient.getTableResource(tableId);
    RowResourceList rowResourceList =
            odkClient.getRowResourceList(tableId, tableResource.getSchemaETag(), "", false);

    if (!showDeleted) {
      rowResourceList.setRows(rowResourceList
              .getRows()
              .stream()
              .filter(row -> !row.isDeleted())
              .collect(Collectors.toCollection(ArrayList::new))
      );
    }

    HttpHeaders headers = new HttpHeaders();

    if (format.equalsIgnoreCase("JSON")) {
      headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=rows.json");
      return new ResponseEntity<>(rowResourceList.getRows(), headers, HttpStatus.OK);
    } else if (format.equalsIgnoreCase("CSV")) {
      headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=rows.csv");
      ArrayList<RowResource> rows = rowResourceList.getRows();

      StringBuilder valueHeader = new StringBuilder();
      if (rows.size() > 0) {
        ArrayList<DataKeyValue> firstRowValues = rows.get(0).getValues();
        for (DataKeyValue value : firstRowValues) {
          valueHeader.append(value.column+ ",");
        }
      }

      StringBuilder body = new StringBuilder();
      body.append("rowId,formId,locale,savepointType,savepointTimestamp,savepointCreator,createUser,lastUpdateUser," +
              "deleted,dataETagAtModification,");
      body.append(valueHeader);
      body.append("defaultAccess,rowOwner,groupReadOnly,groupModify,groupPrivileged,rowETag\n");

      for (RowResource row : rows) {
        body.append(formatData(row.getRowId()) + ",");
        body.append(formatData(row.getFormId()) + ",");
        body.append(formatData(row.getLocale()) + ",");
        body.append(formatData(row.getSavepointType()) + ",");
        body.append(formatData(row.getSavepointTimestamp()) + ",");
        body.append(formatData(row.getSavepointCreator()) + ",");
        body.append(formatData(row.getCreateUser()) + ",");
        body.append(formatData(row.getLastUpdateUser()) + ",");
        body.append(row.isDeleted()); body.append(",");
        body.append(formatData(row.getDataETagAtModification()) + ",");
        ArrayList<DataKeyValue> rowValues = row.getValues();
        for (DataKeyValue value : rowValues) {
          body.append((value.value == null ? "" : value.value) + ",");
        }
        RowFilterScope rfs = row.getRowFilterScope();
        body.append(formatData(rfs.getDefaultAccess().name()) + ",");
        body.append(formatData(rfs.getRowOwner()) + ",");
        body.append(formatData(rfs.getGroupReadOnly()) + ",");
        body.append(formatData(rfs.getGroupModify()) + ",");
        body.append(formatData(rfs.getGroupPrivileged()) + ",");
        body.append(formatData(row.getRowETag()) + "\n");
      }

      return new ResponseEntity<>(body.toString(), headers, HttpStatus.OK);
    }

    return ResponseEntity.notFound().build();
  }

  String formatData(String data) {
    if (data == null) {
      return "";
    } else {
      return data.replaceAll(",", " ");
    }
  }

  Map<String, SurveyQuestion> getSurveyQuestionMap(FormDef rootNode) {
    // TODO: should probably cache this, this is being called by row_detail_script.html for every row

    return rootNode
            .specification
            .sections
            .values()
            .stream()
            .flatMap(section -> section.prompts.stream())
            .filter(this::isQuestionNode)
            .map(promptNode -> new SurveyQuestion(
                    promptNode.type,
                    promptNode.name,
                    getDisplayTextNullSafe(promptNode),
                    promptNode._row_num
            ))
            .collect(Collectors.toMap(SurveyQuestion::getName, Function.identity()));
  }

  boolean isQuestionNode(Prompt node) {
    return node.name != null && node.display != null && node.display.prompt != null;
  }

  String getDisplayTextNullSafe(Prompt node) {
    // TODO: this does not handle translation, or any handlebars template
    // TODO: non-ascii not showing properly on html

    if (node.display == null || node.display.prompt == null) {
      return "";
    }

    if (node.display.prompt.stringTokenRef != null) {
      // TODO: this needs string_token substitution
      return node.display.prompt.stringTokenRef;
    }

    if (!node.display.prompt.text.equals("")) {
      return node.display.prompt.text;
    }

    if (!node.display.prompt.audio.equals("")) {
      return node.display.prompt.audio;
    }

    if (!node.display.prompt.video.equals("")) {
      return node.display.prompt.video;
    }

    if (!node.display.prompt.image.equals("")) {
      return node.display.prompt.image;
    }

    return "";
  }
}
