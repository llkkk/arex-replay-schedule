package com.arextest.schedule.model.config;


import com.arextest.web.model.contract.contracts.compare.CategoryDetail;
import com.arextest.web.model.contract.contracts.compare.TransformDetail;
import com.arextest.web.model.contract.contracts.config.replay.ComparisonSummaryConfiguration.ReplayScriptMethod;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * Created by wang_yc on 2021/10/14
 */
@Data
public class ReplayComparisonConfig {
  private String operationName;
  private List<String> operationTypes;
  private List<CategoryDetail> ignoreCategoryTypes;
  private Set<List<String>> exclusionList;
  private Set<List<String>> inclusionList;

  @JsonDeserialize(keyUsing = MapKeyDeserializerUtils.class)
  @JsonSerialize(keyUsing = MapKeySerializerUtils.class)
  private Map<List<String>, List<String>> referenceMap;

  @JsonDeserialize(keyUsing = MapKeyDeserializerUtils.class)
  @JsonSerialize(keyUsing = MapKeySerializerUtils.class)
  private Map<List<String>, List<List<String>>> listSortMap;

  private List<TransformDetail> transformDetails;

  @JsonDeserialize(keyUsing = MapKeyDeserializerUtils.class)
  @JsonSerialize(keyUsing = MapKeySerializerUtils.class)
  private Map<List<String>, ReplayScriptMethod> scriptMethodMap;

  /**
   * Custom config
   *
   * @see com.arextest.schedule.comparer.CustomComparisonConfigurationHandler
   */
  private Map<String, Object> additionalConfig;

  public void fillCommonFields() {
    this.setExclusionList(Collections.emptySet());
    this.setInclusionList(Collections.emptySet());
    this.setListSortMap(Collections.emptyMap());
    this.setReferenceMap(Collections.emptyMap());
    this.setAdditionalConfig(Collections.emptyMap());
    this.setIgnoreCategoryTypes(Collections.emptyList());
  }

  private static class MapKeyDeserializerUtils extends KeyDeserializer {

    @Override
    public Object deserializeKey(String s, DeserializationContext deserializationContext)
        throws IOException {
      ObjectMapper objectMapper = new ObjectMapper();
      return objectMapper.readValue(s, new TypeReference<List<String>>() {
      });
    }
  }

  private static class MapKeySerializerUtils extends JsonSerializer<List<String>> {

    @Override
    public void serialize(List<String> stringList,
        JsonGenerator jsonGenerator,
        SerializerProvider serializerProvider) throws IOException {
      ObjectMapper objectMapper = new ObjectMapper();
      String string = objectMapper.writeValueAsString(stringList);
      jsonGenerator.writeFieldName(string);
    }
  }

  public static ReplayComparisonConfig empty() {
    ReplayComparisonConfig config = new ReplayComparisonConfig();
    config.fillCommonFields();
    return config;
  }
}