package org.acme.kafka.streams.aggregator.streams;

import java.time.Instant;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import org.acme.kafka.streams.aggregator.model.Aggregation;
import org.acme.kafka.streams.aggregator.model.Attribute;
import org.acme.kafka.streams.aggregator.model.QDataMessageObject;
import org.acme.kafka.streams.aggregator.model.TemperatureMeasurement;
import org.acme.kafka.streams.aggregator.model.WeatherStation;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.connect.transforms.predicates.Predicate;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.Stores;

import io.quarkus.kafka.client.serialization.ObjectMapperSerde;

@ApplicationScoped
public class TopologyProducer {

	 // Set up serializers and deserializers, which we will use for overriding the default serdes
    // specified above.
    final Serde<String> stringSerde = Serdes.String();
    final Serde<byte[]> byteArraySerde = Serdes.ByteArray();
    
    static final String WEATHER_STATIONS_STORE = "weather-stations-store";
    static final String ATTRIBUTES_STORE = "attributes-store";

    static final String WEATHER_STATIONS_TOPIC = "weather-stations";
    static final String ATTRIBUTES_TOPIC = "attributes";
    static final String TEMPERATURE_VALUES_TOPIC = "temperature-values";
    static final String TEMPERATURES_AGGREGATED_TOPIC = "temperatures-aggregated";
    static final String DATA_TOPIC = "data";
    static final String TEST_DATA_TOPIC = "test-data";
    static final String VALIDATED_DATA_TOPIC = "valid_data";

    
    public Boolean validate(String data) {
    	if (data != null && !data.contains("Adaam")) {
    		return true;
    	}
    	return false;
    	
    }
    
    @Produces
    public Topology buildTopology() {
    	ObjectMapperSerde<Attribute> qdatamessageSerde = new ObjectMapperSerde<>(Attribute.class);
    	
    	Attribute attribute = new Attribute();
        StreamsBuilder builder = new StreamsBuilder();
        
        // Read the input Kafka topic into a KStream instance.
        
     
        
        builder
        .stream("data", Consumed.with(Serdes.String(), Serdes.String()))
        .mapValues(attribute::tidy)
        .filter((k, v) -> validate(v))
        .peek((k, v) -> System.out.println("K["+k+"] "+v))
        .to("valid_data", Produced.with(Serdes.String(), Serdes.String()));
 
//        builder
//        .stream("data", Consumed.with(Serdes.String(), Serdes.String()))
//        .mapValues(attribute::tidy)
//        .filter((k, v) -> v != null && !v.isEmpty())
//        .peek((k, v) -> System.out.println(v))
//        .to("valid_data", Produced.with(Serdes.String(), Serdes.String()));

//        builder.stream("test-data", Consumed.with(Serdes.Integer(), Serdes.String()))
//        // Set key to title and value to ticket value
//        .mapValues(v -> v.code.toLowerCase())
//        // Group by title
//       // .groupByKey(Grouped.with(Serdes.String(), Serdes.String()))
//         // Write to stream specified by outputTopic
//        .to("lowercase-data", Produced.with(Serdes.Integer(), Serdes.String()));

        
        
     //   final KStream<String, String> textLines = builder.stream("test-data", Consumed.with(stringSerde, stringSerde));
//        final KStream<byte[], String> textLines = builder.stream("test-data", Consumed.with(byteArraySerde, stringSerde));
               
     // Variant 1: using `mapValues`
     //   final KStream<byte[], String> lowercasedWithMapValues = textLines.mapValues(v -> v.toLowerCase());
        
     // Write (i.e. persist) the results to a new Kafka topic called "UppercasedTextLinesTopic".
        //
        // In this case we can rely on the default serializers for keys and values because their data
        // types did not change, i.e. we only need to provide the name of the output topic.
     //   lowercasedWithMapValues.to("lowercase-data");
        
        // Variant 2: using `map`, modify value only (equivalent to variant 1)
     //   final KStream<byte[], String> uppercasedWithMap = textLines.map((key, value) -> new KeyValue<>(key, value.toUpperCase()));

        // Variant 3: using `map`, modify both key and value
        //
        // Note: Whether, in general, you should follow this artificial example and store the original
        //       value in the key field is debatable and depends on your use case.  If in doubt, don't
        //       do it.
    //    final KStream<String, String> originalAndUppercased = textLines.map((key, value) -> KeyValue.pair(value, value.toUpperCase()));

        // Write the results to a new Kafka topic "OriginalAndUppercasedTopic".
        //
        // In this case we must explicitly set the correct serializers because the default serializers
        // (cf. streaming configuration) do not match the type of this particular KStream instance.
       // originalAndUppercased.to("OriginalAndUppercasedTopic", Produced.with(stringSerde, stringSerde));

//        ObjectMapperSerde<WeatherStation> weatherStationSerde = new ObjectMapperSerde<>(WeatherStation.class);
//        ObjectMapperSerde<Attribute> attributeSerde = new ObjectMapperSerde<>(Attribute.class);
//        ObjectMapperSerde<Aggregation> aggregationSerde = new ObjectMapperSerde<>(Aggregation.class);
//
//        KeyValueBytesStoreSupplier storeSupplier = Stores.persistentKeyValueStore(WEATHER_STATIONS_STORE);
// 
//
//        GlobalKTable<Integer, WeatherStation> stations = builder.globalTable(
//                WEATHER_STATIONS_TOPIC,
//                Consumed.with(Serdes.Integer(), weatherStationSerde));
//        
//        GlobalKTable<Integer, Attribute> attributes = builder.globalTable(
//                ATTRIBUTES_TOPIC,
//                Consumed.with(Serdes.Integer(), attributeSerde));
//
//
//        builder.stream(TEST_DATA_TOPIC,Consumed.with(Serdes.Integer(), Serdes.String()))
//        .to(VALIDATED_DATA_TOPIC,Produced.with(Serdes.Integer(), Serdes.String()));
//        builder.stream(
//                DATA_TOPIC,
//                Consumed.with(Serdes.Integer(), Serdes.String()))
////                .join(
////                        stations,
////                        (stationId, timestampAndValue) -> stationId,
////                        (timestampAndValue, station) -> {
////                            String[] parts = timestampAndValue.split(";");
////                            return new TemperatureMeasurement(station.id, station.name, Instant.parse(parts[0]),
////                                    Double.valueOf(parts[1]));
////                        })
//                .groupByKey()
//                .aggregate(
//                        QDataMessageObject::new,
//                        (stationId, value, qdmsg) -> qdmsg.updateFrom(value),
//                        Materialized.<Integer, QDataMessageObject> as(storeSupplier)
//                                .withKeySerde(Serdes.Integer())
//                                .withValueSerde(aggregationSerde))
//                .toStream()
//                .to(
//                        VALIDATED_DATA_TOPIC,
//                        Produced.with(Serdes.Integer(), aggregationSerde));

//        builder.stream(
//                TEMPERATURE_VALUES_TOPIC,
//                Consumed.with(Serdes.Integer(), Serdes.String()))
//                .join(
//                        stations,
//                        (stationId, timestampAndValue) -> stationId,
//                        (timestampAndValue, station) -> {
//                            String[] parts = timestampAndValue.split(";");
//                            return new TemperatureMeasurement(station.id, station.name, Instant.parse(parts[0]),
//                                    Double.valueOf(parts[1]));
//                        })
//                .groupByKey()
//                .aggregate(
//                        Aggregation::new,
//                        (stationId, value, aggregation) -> aggregation.updateFrom(value),
//                        Materialized.<Integer, Aggregation> as(storeSupplier)
//                                .withKeySerde(Serdes.Integer())
//                                .withValueSerde(aggregationSerde))
//                .toStream()
//                .to(
//                        TEMPERATURES_AGGREGATED_TOPIC,
//                        Produced.with(Serdes.Integer(), aggregationSerde));

        return builder.build();
    }
}
