package com.salesforce.dva.argus.service.schema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.salesforce.dva.argus.entity.ScopeOnlySchemaRecord;
import com.salesforce.dva.argus.service.SchemaService.RecordType;
import com.salesforce.dva.argus.service.schema.MetricSchemaRecordList.HashAlgorithm;

import net.openhft.hashing.LongHashFunction;

/**
 * Represents a list of scope names from discovery queries.
 * Internally it has a mapping from hash id of scope names to the actual scope names.
 *
 * @author  Dilip Devaraj (ddevaraj@salesforce.com)
 */
public class ScopeOnlySchemaRecordList {
	
	private Map<String, ScopeOnlySchemaRecord> _idToSchemaRecordMap = new HashMap<>();
	private String _scrollID;

	public ScopeOnlySchemaRecordList(List<ScopeOnlySchemaRecord> records, String scrollID) {
		int count = 0;
		for(ScopeOnlySchemaRecord record : records) {
			_idToSchemaRecordMap.put(String.valueOf(count++), record);
		}
		setScrollID(scrollID);
	}
	
	public ScopeOnlySchemaRecordList(List<ScopeOnlySchemaRecord> records, HashAlgorithm algorithm) {
		for(ScopeOnlySchemaRecord record : records) {
			String id = null;
			String scopeOnly = record.getScope();
			if(HashAlgorithm.MD5.equals(algorithm)) {
				id = DigestUtils.md5Hex(scopeOnly);
			} else {
				id = String.valueOf(LongHashFunction.xx().hashChars(scopeOnly));
			}
			_idToSchemaRecordMap.put(id, new ScopeOnlySchemaRecord(scopeOnly));
		}
	}
	
	public List<ScopeOnlySchemaRecord> getRecords() {
		return new ArrayList<>(_idToSchemaRecordMap.values());
	}
	
	public String getScrollID() {
		return _scrollID;
	}

	public void setScrollID(String scrollID) {
		this._scrollID = scrollID;
	}
	
	ScopeOnlySchemaRecord getRecord(String id) {
		return _idToSchemaRecordMap.get(id);
	}
	
	static class Serializer extends JsonSerializer<ScopeOnlySchemaRecordList> {

		@Override
		public void serialize(ScopeOnlySchemaRecordList list, JsonGenerator jgen, SerializerProvider provider)
				throws IOException, JsonProcessingException {
			
			ObjectMapper mapper = new ObjectMapper();
			mapper.setSerializationInclusion(Include.NON_NULL);
			
			for(Map.Entry<String, ScopeOnlySchemaRecord> entry : list._idToSchemaRecordMap.entrySet()) {
				jgen.writeRaw("{ \"index\" : {\"_id\" : \"" + entry.getKey() + "\"}}");
				jgen.writeRaw(System.lineSeparator());
				String fieldsData = mapper.writeValueAsString(entry.getValue());
				String timeStampField = "\"mts\":" + System.currentTimeMillis();
				jgen.writeRaw(fieldsData.substring(0, fieldsData.length()-1) + "," + timeStampField + "}");
				jgen.writeRaw(System.lineSeparator());
			}
		}
    }
	
	static class Deserializer extends JsonDeserializer<ScopeOnlySchemaRecordList> {

		@Override
		public ScopeOnlySchemaRecordList deserialize(JsonParser jp, DeserializationContext context)
				throws IOException, JsonProcessingException {
			
			String scrollID = null;
			List<ScopeOnlySchemaRecord> records = Collections.emptyList();
			
			JsonNode rootNode = jp.getCodec().readTree(jp);
			if(rootNode.has("_scroll_id")) {
				scrollID = rootNode.get("_scroll_id").asText();
			}
			JsonNode hits = rootNode.get("hits").get("hits");
			
			if(JsonNodeType.ARRAY.equals(hits.getNodeType())) {
				records = new ArrayList<>(hits.size());
				Iterator<JsonNode> iter = hits.elements();
				while(iter.hasNext()) {
					JsonNode hit = iter.next();
					JsonNode source = hit.get("_source");
					
					JsonNode scopeNode = source.get(RecordType.SCOPE.getName());
					
					records.add(new ScopeOnlySchemaRecord(scopeNode.asText()));
				}
			}
			
			return new ScopeOnlySchemaRecordList(records, scrollID);
		}
	}
	
	static class AggDeserializer extends JsonDeserializer<List<String>> {

		@Override
		public List<String> deserialize(JsonParser jp, DeserializationContext context)
				throws IOException, JsonProcessingException {
			
			List<String> values = Collections.emptyList();
			
			JsonNode rootNode = jp.getCodec().readTree(jp);
			JsonNode buckets = rootNode.get("aggregations").get("distinct_values").get("buckets");
			
			if(JsonNodeType.ARRAY.equals(buckets.getNodeType())) {
				values = new ArrayList<>(buckets.size());
				Iterator<JsonNode> iter = buckets.elements();
				while(iter.hasNext()) {
					JsonNode bucket = iter.next();
					String value  = bucket.get("key").asText();
					values.add(value);
				}
			}
			
			return values;
		}
	}
}
