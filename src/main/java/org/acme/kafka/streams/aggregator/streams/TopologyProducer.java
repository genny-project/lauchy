package org.acme.kafka.streams.aggregator.streams;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.Period;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.net.ssl.HttpsURLConnection;

import org.acme.kafka.streams.aggregator.model.ApiBridgeService;
import org.acme.kafka.streams.aggregator.model.ApiQwandaService;
import org.acme.kafka.streams.aggregator.model.ApiService;
import org.acme.kafka.streams.aggregator.model.Attribute2;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import life.genny.models.GennyToken;
import life.genny.qwanda.Answer;
import life.genny.qwanda.attribute.Attribute;
import life.genny.qwanda.attribute.AttributeText;
import life.genny.qwanda.attribute.EntityAttribute;
import life.genny.qwanda.datatype.DataType;
import life.genny.qwanda.entity.BaseEntity;
import life.genny.qwanda.entity.SearchEntity;
import life.genny.qwanda.exception.BadDataException;
import life.genny.qwanda.exception.DebugException;
import life.genny.qwanda.message.QDataAnswerMessage;
import life.genny.qwanda.message.QDataAttributeMessage;
import life.genny.qwanda.message.QDataBaseEntityMessage;
import life.genny.qwanda.validation.Validation;

@ApplicationScoped
public class TopologyProducer {

	private static final Logger log = Logger.getLogger(TopologyProducer.class);

	@Inject
	InternalProducer producer;

	@Inject
	@RestClient
	ApiService apiService;

	@Inject
	@RestClient
	ApiQwandaService apiQwandaService;

	@Inject
	@RestClient
	ApiBridgeService apiBridgeService;

	@ConfigProperty(name = "genny.show.values", defaultValue = "false")
	Boolean showValues;
	
	@ConfigProperty(name = "genny.enable.blacklist", defaultValue = "true")
	Boolean enableBlacklist;


	@ConfigProperty(name = "genny.keycloak.url", defaultValue = "https://keycloak.gada.io")
	String baseKeycloakUrl;

	@ConfigProperty(name = "genny.keycloak.realm", defaultValue = "genny")
	String keycloakRealm;

	@ConfigProperty(name = "genny.service.username", defaultValue = "service")
	String serviceUsername;

	@ConfigProperty(name = "genny.service.password", defaultValue = "password")
	String servicePassword;

	@ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "https://keycloak.genny.life/auth/realms/genny")
	String keycloakUrl;

	@ConfigProperty(name = "genny.oidc.client-id", defaultValue = "backend")
	String clientId;

	@ConfigProperty(name = "genny.oidc.credentials.secret", defaultValue = "secret")
	String secret;

	@ConfigProperty(name = "genny.api.url", defaultValue = "http://alyson.genny.life:8280")
	String apiUrl;

	@ConfigProperty(name = "user.eligible.age", defaultValue = "18")
	String userEligibleAge;

	GennyToken serviceToken;

	static public Map<String, Map<String, Attribute>> realmAttributeMap = new ConcurrentHashMap<>();
	static public Map<String, Map<String, BaseEntity>> defs = new ConcurrentHashMap<>(); // realm and DEF lookup
	static public QDataAttributeMessage attributesMsg = null;

	// custom executor
//		private static final ExecutorService executorService = Executors.newFixedThreadPool(20);

//		private static HttpClient httpClient = HttpClient.newBuilder().executor(executorService)
//				.version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(20)).build();

//	static GsonBuilder gsonBuilder = new GsonBuilder();       
//
//	static public Gson gson = gsonBuilder.registerTypeAdapter(Money.class, new MoneyDeserializer())
//			.registerTypeAdapter(LocalDateTime.class, new DateTimeDeserializer())
//			.registerTypeAdapter(LocalDate.class, new LocalDateConverter())
//		//	.excludeFieldsWithModifiers(Modifier.STATIC, Modifier.TRANSIENT, Modifier.VOLATILE)
//			.excludeFieldsWithoutExposeAnnotation()
//		//    .disableHtmlEscaping()
//		    .setPrettyPrinting()
//			.create();

	// Set up serializers and deserializers, which we will use for overriding the
	// default serdes
	// specified above.

	Jsonb jsonb = JsonbBuilder.create();

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
	static final String BLACKLIST_TOPIC = "blacklist";

	public static Boolean isValidABN(final String abnCode) {
		// Thanks to "Joker" from stackOverflow -
		// https://stackoverflow.com/users/3949925/joker
		Boolean ret = false;
		if (abnCode.matches("[0-9]+") && abnCode.length() != 11) {
			return false;
		}
		final int[] weights = { 10, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19 };
		// split abn number string by digits to get int array
		int[] abnDigits = Stream.of(abnCode.split("\\B")).mapToInt(Integer::parseInt).toArray();
		// reduce by applying weight[index] * abnDigits[index] (NOTE: substract 1 for
		// the first digit in abn number)
		int sum = IntStream.range(0, weights.length).reduce(0,
				(total, idx) -> total + weights[idx] * (idx == 0 ? abnDigits[idx] - 1 : abnDigits[idx]));
		return (sum % 89 == 0);
	}

	public static boolean isValidCreditCard(String ccNumber) {
		int sum = 0;
		boolean alternate = false;
		for (int i = ccNumber.length() - 1; i >= 0; i--) {
			int n = Integer.parseInt(ccNumber.substring(i, i + 1));
			if (alternate) {
				n *= 2;
				if (n > 9) {
					n = (n % 10) + 1;
				}
			}
			sum += n;
			alternate = !alternate;
		}
		return (sum % 10 == 0);
	}

	public boolean isEligibleAge(String dob) {
		if ((dob != null) && (!dob.isEmpty())) {
			try {
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
				LocalDateTime dateTime = LocalDateTime.parse(dob, formatter);
				LocalDateTime now = LocalDateTime.now();
				/*calculates the years diff between two dates and return bool, is eligible*/
				int year = Period.between(dateTime.toLocalDate(), now.toLocalDate()).getYears();
				return (year >= Integer.parseInt(userEligibleAge));
			} catch (Exception e) {
				System.out.println("Error: Could not get dob year diff :: " + e.getMessage());
			}
		}
		return false;
	}

	public Boolean validate(String data) {
		Boolean valid = true;
		String uuid = null;
		String realm = null;
		GennyToken userToken = null;
		

		if (data != null && !data.contains("Adaam")) {
			try {
				JsonObject json = jsonb.fromJson(data, JsonObject.class);

				String msgType = json.getString("msg_type");
				String msgDataType = json.getString("data_type");

				if ("DATA_MSG".equals(msgType) && ("Answer".equals(msgDataType))) {
//					//log.info(json);
					userToken = new GennyToken(json.getString("token"));

					// JsonObject decoded = getDecodedToken(userToken);
//					uuid = decoded.getString("sub");
//					if (decoded.get("iss") != null) {
//						String[] issArray = decoded.get("iss").toString().split("/");
//						realm = issArray[issArray.length - 1];
//						realm =  realm.substring(0, realm.length()-1);
//					} else if (decoded.get("azp") != null) {
//						realm = (decoded.get("azp").toString()); // clientid
//					}
					JsonArray items = json.getJsonArray("items");
					log.info(userToken);
					QDataAnswerMessage answerMsg = jsonb.fromJson(data, QDataAnswerMessage.class);
//					userToken = new GennyToken(answerMsg.getToken());
					if (!userToken.getToken().equals(answerMsg.getToken())) {
						log.error("Message Token and userToken DO NOT Match for " + userToken.getEmail());
						valid = false;
					}
//					
					for (Answer answer : answerMsg.getItems()) {
						System.out.println("answer : " + answer.getAttributeCode());
						System.out.println("realm : " + userToken.getRealm());

//						JsonObject answerJson = answer.asJsonObject();
//						// TODO, check questionCode by fetching from questions 5
//						// TODO check askID by fetching from Tasks
//						String sourceCode = answerJson.getString("sourceCode");
						if (!(userToken.getUserCode()).equals(answer.getSourceCode())) {
							valid = false;
						} else {
							// check source code exists
							// JsonObject source = fetchDataFromCache(sourceCode,userToken);
							BaseEntity sourceBe = null;

							sourceBe = fetchBaseEntityFromCache(answer.getSourceCode(), serviceToken);

							log.info("Source = " + sourceBe.getCode() + ":" + sourceBe.getName());
							if (sourceBe != null) {
								// Check Target exist
								BaseEntity targetBe = fetchBaseEntityFromCache(answer.getTargetCode(), serviceToken);
								if (targetBe != null) {
									BaseEntity defBe = getDEF(targetBe, serviceToken);
									// check attribute code is allowed by targetDEF
									if (defBe.containsEntityAttribute("ATT_" + answer.getAttributeCode())) {
//										// Now validate values
										Attribute attribute = getAttribute(answer.getAttributeCode(),
												serviceToken.getToken());
										if (attribute != null) {
											DataType dataType = attribute.getDataType();
											// HACK: TODO ACC - To send back an emoty LNK_PERSON for a bucket search
											if ("LNK_PERSON".equals(answer.getAttributeCode())) {
												if ("BKT_APPLICATIONS".equals(answer.getTargetCode())) {
													if ("[]".equals(answer.getValue())) {
													// So send back a dummy empty value for the LNK_PERSON
														targetBe.setValue(attribute, "[]");
														QDataBaseEntityMessage responseMsg = new QDataBaseEntityMessage(targetBe);
														responseMsg.setTotal(1L);
														responseMsg.setReturnCount(1L);
														responseMsg.setToken(userToken.getToken());
														String jsonMsg= jsonb.toJson(responseMsg);
													
														producer.getToWebData().send(jsonMsg);
														log.info("Detected cleared BKT_APPLICATIONS search from "+userToken.getEmailUserCode()+" sent this json->"+jsonMsg);
													}
												}
											}
											if ("PRI_ABN".equals(answer.getAttributeCode())) {
												valid = isValidABN(answer.getValue());
											} else if ("PRI_CREDITCARD".equals(answer.getAttributeCode())) {
												valid = isValidCreditCard(answer.getValue());
											} else if (("PRI_DATE_OF_BIRTH".equals(answer.getAttributeCode())) && ("mentormatch".equalsIgnoreCase(userToken.getRealm()))) {
												valid = isEligibleAge(answer.getValue());
											} else {
												Boolean isAnyValid = false;
												for (Validation validation : dataType.getValidationList()) {
													// Now check the validation
													String regex = validation.getRegex();
													// TODO speedup by precompiling all validations
													boolean regexOk = Pattern.compile(regex).matcher(answer.getValue())
															.matches();
													if (regexOk) {
														isAnyValid = true;
														log.info("Regex OK! [" + answer.getValue() + "] for regex "
																+ regex);
														break;
													}
													log.info("Regex failed! Att:[" + answer.getAttributeCode() + "]"
															+ attribute.getDataType().getDttCode() + " ["
															+ answer.getValue() + "] for regex " + regex + " ..."
															+ validation.getErrormsg());
												}
												valid = isAnyValid;
											}
										} else {
											valid = false;
											log.error("AttributeCode" + answer.getAttributeCode() + " not existing "
													+ defBe.getCode());
										}
									} else {
										valid = false;
										log.error("AttributeCode" + answer.getAttributeCode() + " not allowed for "
												+ defBe.getCode());
									}

								} else {
									valid = false;
									log.error("Target " + answer.getTargetCode() + " does not exist");
								}
							} else {
								valid = false;
								log.error("Source " + answer.getSourceCode() + " does not exist");
							}
						}
					}
				}
			} catch (Exception e) {

				valid = false;
			}

		}
		if (!valid) {
			uuid = userToken.getUuid();
			log.info("BLACKLIST "+(enableBlacklist?"ON":"OFF")+" " + userToken.getEmail() + ":" + uuid);
			try {
				// apiBridgeService.addBlacklistUUID(uuid, "Bearer "+serviceToken.getToken());
				if (!enableBlacklist) {
					valid = true;
				} else {
					producer.getToBlacklists().send(uuid);
				}
			} catch (Exception e) {
				log.error("Could not add uuid to blacklist api " + uuid);
			}

		}
		return valid;
	}

	@Produces
	public Topology buildTopology() {

		if (showValues) {
			log.info("service username :" + serviceUsername);
			log.info("service password :" + servicePassword);
			log.info("keycloakUrl      :" + keycloakUrl);
			log.info("keycloak clientId:" + clientId);
			log.info("keycloak secret  :" + secret);
			log.info("keycloak realm   :" + keycloakRealm);
			log.info("api Url          :" + apiUrl);
			log.info("Blacklist        :" + (enableBlacklist?"ON":"OFF"));
		}

		try {
			serviceToken = getToken(serviceUsername, servicePassword);
			loadAllAttributesIntoCache(serviceToken);
			setUpDefs(serviceToken);
		} catch (IOException e) {
			log.error("Cannot obtain Service Token for " + keycloakUrl + " and " + keycloakRealm);
		} catch (BadDataException e) {
			log.error("Cannot set up DEFs for " + keycloakUrl + " and " + keycloakRealm);
		}

//		ObjectMapperSerde<Attribute> qdatamessageSerde = new ObjectMapperSerde<>(Attribute.class);
//
		Attribute2 attribute = new Attribute2();
		StreamsBuilder builder = new StreamsBuilder();

		// Read the input Kafka topic into a KStream instance.

		builder.stream("data", Consumed.with(Serdes.String(), Serdes.String())).mapValues(attribute::tidy)
				.filter((k, v) -> {
					boolean validationAns = validate(v);
					System.out.println("key: " + k);
					System.out.println("value: " + v);
					System.out.println("validationAns: " + validationAns);
					return validationAns;
				})
				.peek((k, v) -> System.out.log.info("K[" + k + "] " + v))
				.to("valid_data", Produced.with(Serdes.String(), Serdes.String()));

//        builder
//        .stream("data", Consumed.with(Serdes.String(), Serdes.String()))
//        .mapValues(attribute::tidy)
//        .filter((k, v) -> v != null && !v.isEmpty())
//        .peek((k, v) -> System.out.log.info(v))
//        .to("valid_data", Produced.with(Serdes.String(), Serdes.String()));

//        builder.stream("test-data", Consumed.with(Serdes.Integer(), Serdes.String()))
//        // Set key to title and value to ticket value
//        .mapValues(v -> v.code.toLowerCase())
//        // Group by title
//       // .groupByKey(Grouped.with(Serdes.String(), Serdes.String()))
//         // Write to stream specified by outputTopic
//        .to("lowercase-data", Produced.with(Serdes.Integer(), Serdes.String()));

		// final KStream<String, String> textLines = builder.stream("test-data",
		// Consumed.with(stringSerde, stringSerde));
//        final KStream<byte[], String> textLines = builder.stream("test-data", Consumed.with(byteArraySerde, stringSerde));

		// Variant 1: using `mapValues`
		// final KStream<byte[], String> lowercasedWithMapValues = textLines.mapValues(v
		// -> v.toLowerCase());

		// Write (i.e. persist) the results to a new Kafka topic called
		// "UppercasedTextLinesTopic".
		//
		// In this case we can rely on the default serializers for keys and values
		// because their data
		// types did not change, i.e. we only need to provide the name of the output
		// topic.
		// lowercasedWithMapValues.to("lowercase-data");

		// Variant 2: using `map`, modify value only (equivalent to variant 1)
		// final KStream<byte[], String> uppercasedWithMap = textLines.map((key, value)
		// -> new KeyValue<>(key, value.toUpperCase()));

		// Variant 3: using `map`, modify both key and value
		//
		// Note: Whether, in general, you should follow this artificial example and
		// store the original
		// value in the key field is debatable and depends on your use case. If in
		// doubt, don't
		// do it.
		// final KStream<String, String> originalAndUppercased = textLines.map((key,
		// value) -> KeyValue.pair(value, value.toUpperCase()));

		// Write the results to a new Kafka topic "OriginalAndUppercasedTopic".
		//
		// In this case we must explicitly set the correct serializers because the
		// default serializers
		// (cf. streaming configuration) do not match the type of this particular
		// KStream instance.
		// originalAndUppercased.to("OriginalAndUppercasedTopic",
		// Produced.with(stringSerde, stringSerde));

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


	public QDataAttributeMessage loadAllAttributesIntoCache(final GennyToken token) {
		try {
			boolean cacheWorked = false;
			String realm = token.getRealm();
			log.info("All the attributes about to become loaded ... for realm " + realm);
			log.info("LOADING ATTRIBUTES FROM API");
			String jsonString = apiQwandaService.getAttributes("Bearer " + token.getToken());
			if (!StringUtils.isBlank(jsonString)) {

				attributesMsg = jsonb.fromJson(jsonString, QDataAttributeMessage.class);
				Attribute[] attributeArray = attributesMsg.getItems();

				if (!realmAttributeMap.containsKey(realm)) {
					realmAttributeMap.put(realm, new ConcurrentHashMap<String, Attribute>());
				}
				Map<String, Attribute> attributeMap = realmAttributeMap.get(realm);

				for (Attribute attribute : attributeArray) {
					attributeMap.put(attribute.getCode(), attribute);
				}
				// realmAttributeMap.put(realm, attributeMap);

				log.info("All the attributes have been loaded from api in " + attributeMap.size() + " attributes");
			} else {
				log.error("NO ATTRIBUTES LOADED FROM API");
			}

			return attributesMsg;
		} catch (Exception e) {
			log.error("Attributes API not available, exception:" + e.getMessage());
			e.printStackTrace();
		}
		return null;
	}

	public QDataAttributeMessage loadAllAttributesIntoCache(final String token) {
		return loadAllAttributesIntoCache(new GennyToken(token));
	}

	public Map<String, BaseEntity> getDefMap(final GennyToken userToken) {
		if ((defs == null) || (defs.isEmpty())) {
			// Load in Defs
			try {
				setUpDefs(userToken);
				return defs.get(userToken.getRealm());
			} catch (BadDataException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return defs.get(userToken.getRealm());
	}

	public BaseEntity getDEF(final BaseEntity be, final GennyToken userToken) {
		if (be == null) {
			log.error("be param is NULL");
			try {
				throw new DebugException("BaseEntityUtils: getDEF: The passed BaseEntity is NULL, supplying trace");
			} catch (DebugException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return null;
		}

		if (be.getCode().startsWith("DEF_")) {
			return be;
		}
		// Some quick ones
		if (be.getCode().startsWith("PRJ_")) {
			BaseEntity defBe = defs.get(userToken.getRealm()).get("DEF_PROJECT");
			return defBe;
		}

		Set<EntityAttribute> newMerge = new HashSet<>();
		List<EntityAttribute> isAs = be.findPrefixEntityAttributes("PRI_IS_");

		// remove the non DEF ones
		/*
		 * PRI_IS_DELETED PRI_IS_EXPANDABLE PRI_IS_FULL PRI_IS_INHERITABLE PRI_IS_PHONE
		 * (?) PRI_IS_SKILLS
		 */
		Iterator<EntityAttribute> i = isAs.iterator();
		while (i.hasNext()) {
			EntityAttribute ea = i.next();

			if (ea.getAttributeCode().startsWith("PRI_IS_APPLIED_")) {

				i.remove();
			} else {
				switch (ea.getAttributeCode()) {
				case "PRI_IS_DELETED":
				case "PRI_IS_EXPANDABLE":
				case "PRI_IS_FULL":
				case "PRI_IS_INHERITABLE":
				case "PRI_IS_PHONE":
				case "PRI_IS_AGENT_PROFILE_GRP":
				case "PRI_IS_BUYER_PROFILE_GRP":
				case "PRI_IS_EDU_PROVIDER_STAFF_PROFILE_GRP":
				case "PRI_IS_REFERRER_PROFILE_GRP":
				case "PRI_IS_SELLER_PROFILE_GRP":
				case "PRI_IS SKILLS":
					log.warn("getDEF -> detected non DEFy attributeCode " + ea.getAttributeCode());
					i.remove();
					break;
				case "PRI_IS_DISABLED":
					log.warn("getDEF -> detected non DEFy attributeCode " + ea.getAttributeCode());
					// don't remove until we work it out...
					try {
						throw new DebugException("Bad DEF " + ea.getAttributeCode());
					} catch (DebugException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					break;
				case "PRI_IS_LOGBOOK":
					log.debug("getDEF -> detected non DEFy attributeCode " + ea.getAttributeCode());
					i.remove();

				default:

				}
			}
		}

		if (isAs.size() == 1) {
			// Easy
			Map<String, BaseEntity> beMapping = getDefMap(userToken);
			String attrCode = isAs.get(0).getAttributeCode();

			String trimedAttrCode = attrCode.substring("PRI_IS_".length());

			BaseEntity defBe = beMapping.get("DEF_" + trimedAttrCode);

//			BaseEntity defBe = RulesUtils.defs.get(be.getRealm())
//					.get("DEF_" + isAs.get(0).getAttributeCode().substring("PRI_IS_".length()));
			if (defBe == null) {
				log.error(
						"No such DEF called " + "DEF_" + isAs.get(0).getAttributeCode().substring("PRI_IS_".length()));
			}
			return defBe;
		} else if (isAs.isEmpty()) {
			// THIS HANDLES CURRENT BAD BEs
			// loop through the defs looking for matching prefix
			for (BaseEntity defBe : defs.get(userToken.getRealm()).values()) {
				String prefix = defBe.getValue("PRI_PREFIX", null);
				if (prefix == null) {
					continue;
				}
				// LITTLE HACK FOR OHS DOCS, SORRY!
				if (prefix.equals("DOC") && be.getCode().startsWith("DOC_OHS_")) {
					continue;
				}
				if (be.getCode().startsWith(prefix + "_")) {
					return defBe;
				}
			}

			log.error("NO DEF ASSOCIATED WITH be " + be.getCode());
			return new BaseEntity("ERR_DEF", "No DEF");
		} else {
			// Create sorted merge code
			String mergedCode = "DEF_" + isAs.stream().sorted(Comparator.comparing(EntityAttribute::getAttributeCode))
					.map(ea -> ea.getAttributeCode()).collect(Collectors.joining("_"));
			mergedCode = mergedCode.replaceAll("_PRI_IS_DELETED", "");
			BaseEntity mergedBe = defs.get(userToken.getRealm()).get(mergedCode);
			if (mergedBe == null) {
				log.info("Detected NEW Combination DEF - " + mergedCode);
				// Get primary PRI_IS
				Optional<EntityAttribute> topDog = be.getHighestEA("PRI_IS_");
				if (topDog.isPresent()) {
					String topCode = topDog.get().getAttributeCode().substring("PRI_IS_".length());
					BaseEntity defTopDog = defs.get(userToken.getRealm()).get("DEF_" + topCode);
					mergedBe = new BaseEntity(mergedCode, mergedCode); // So this combination DEF inherits top dogs name
					// now copy all the combined DEF eas.
					for (EntityAttribute isea : isAs) {
						BaseEntity defEa = defs.get(userToken.getRealm())
								.get("DEF_" + isea.getAttributeCode().substring("PRI_IS_".length()));
						if (defEa != null) {
							for (EntityAttribute ea : defEa.getBaseEntityAttributes()) {
								try {
									mergedBe.addAttribute(ea);
								} catch (BadDataException e) {
									log.error("Bad data in getDEF ea merge " + mergedCode);
								}
							}
						} else {
							log.info(
									"No DEF code -> " + "DEF_" + isea.getAttributeCode().substring("PRI_IS_".length()));
							return null;
						}
					}
					defs.get(userToken.getRealm()).put(mergedCode, mergedBe);
					return mergedBe;

				} else {
					log.error("NO DEF EXISTS FOR " + be.getCode());
					return null;
				}
			} else {
				return mergedBe; // return 'merged' composite
			}
		}

	}

	public void setUpDefs(GennyToken userToken) throws BadDataException {

		SearchEntity searchBE = new SearchEntity("SBE_DEF", "DEF check")
				.addSort("PRI_NAME", "Created", SearchEntity.Sort.ASC)
				.addFilter("PRI_CODE", SearchEntity.StringFilter.LIKE, "DEF_%").addColumn("PRI_CODE", "Name");

		searchBE.setRealm(userToken.getRealm());
		searchBE.setPageStart(0);
		searchBE.setPageSize(1000);

		List<BaseEntity> items = getBaseEntitys(searchBE, userToken);
		// Load up RuleUtils.defs

		defs.put(userToken.getRealm(), new ConcurrentHashMap<String, BaseEntity>());

		for (BaseEntity item : items) {
//            if the item is a def appointment, then add a default datetime for the start (Mandatory)
			if (item.getCode().equals("DEF_APPOINTMENT")) {
				Attribute attribute = new AttributeText("DFT_PRI_START_DATETIME", "Default Start Time");
				attribute.setRealm(userToken.getRealm());
				EntityAttribute newEA = new EntityAttribute(item, attribute, 1.0, "2021-07-28 00:00:00");
				item.addAttribute(newEA);

				Optional<EntityAttribute> ea = item.findEntityAttribute("ATT_PRI_START_DATETIME");
				if (ea.isPresent()) {
					ea.get().setValue(true);
				}
			}

//            Save the BaseEntity created
			item.setFastAttributes(true); // make fast
			defs.get(userToken.getRealm()).put(item.getCode(), item);
			log.info("Saving (" + userToken.getRealm() + ") DEF " + item.getCode());
		}
	}

	public Attribute getAttribute(final String attributeCode, final String token) {
		GennyToken gennyToken = new GennyToken(token);
		return getAttribute(attributeCode, gennyToken);
	}

	public Attribute getAttribute(final String attributeCode, final GennyToken gennyToken) {
		String realm = gennyToken.getRealm();
		if (!realmAttributeMap.containsKey(realm)) {
			loadAllAttributesIntoCache(gennyToken);
		}
		Attribute ret = realmAttributeMap.get(gennyToken.getRealm()).get(attributeCode);
		if (ret == null) {
			if (attributeCode.startsWith("SRT_") || attributeCode.startsWith("RAW_")) {
				ret = new AttributeText(attributeCode, attributeCode);
			} else {
				loadAllAttributesIntoCache(gennyToken);
				ret = realmAttributeMap.get(gennyToken.getRealm()).get(attributeCode);
				if (ret == null) {
					log.error("Attribute NOT FOUND :" + realm + ":" + attributeCode);
				}
			}
		}
		return ret;
	}


	public JsonObject fetchDataFromCache(final String code, final GennyToken token) {
		String data = null;
		String value = null;
		try {
			data = apiService.getDataFromCache(token.getRealm(), code, "Bearer " + token.getToken());
			JsonObject json = jsonb.fromJson(data, JsonObject.class);
			if ("ok".equalsIgnoreCase(json.getString("status"))) {
				value = json.getString("value");
				// log.info(value);
			}
		} catch (Exception e) {
			log.error("Failed to read cache for data" + code + ", exception:" + e.getMessage());
			e.printStackTrace();
		}
		return jsonb.fromJson(value, JsonObject.class);
	}

	public BaseEntity fetchBaseEntityFromCache(final String code, final GennyToken token) {
		String data = null;
		String value = null;

		data = apiService.getDataFromCache(token.getRealm(), code, "Bearer " + token.getToken());
		JsonObject json = jsonb.fromJson(data, JsonObject.class);
		if ("ok".equalsIgnoreCase(json.getString("status"))) {
			value = json.getString("value");
			// log.info(value);
			return jsonb.fromJson(value, BaseEntity.class);
		}
		return null;
	}

	public JsonObject fetchSearchResults(final String searchBE, final GennyToken token) {
		String data = null;
		String value = null;
		try {
			data = apiQwandaService.getSearchResults(searchBE, "Bearer " + token.getToken());
			JsonObject json = jsonb.fromJson(data, JsonObject.class);
			if ("ok".equalsIgnoreCase(json.getString("status"))) {
				value = json.getString("value");
				// log.info(value);
			}
		} catch (Exception e) {
			log.error("Failed to get Results for search " + e.getMessage());
			e.printStackTrace();
		}
		return jsonb.fromJson(value, JsonObject.class);
	}


	public JsonObject getDecodedToken(final String bearerToken) {
		final String[] chunks = bearerToken.split("\\.");
		Base64.Decoder decoder = Base64.getDecoder();
//			String header = new String(decoder.decode(chunks[0]));
		String payload = new String(decoder.decode(chunks[1]));
		JsonObject json = jsonb.fromJson(payload, JsonObject.class);
		return json;
	}

	/**
	 * @param searchBE
	 * @return
	 */
	public List<BaseEntity> getBaseEntitys(final SearchEntity searchBE, GennyToken serviceToken) {
		List<BaseEntity> results = new ArrayList<BaseEntity>();

		try {
			log.info("creating searchJson for " + searchBE.getCode());
			String searchJson = jsonb.toJson(searchBE);
			log.info("Fetching baseentitys for " + searchBE.getCode());
			String resultJsonStr = apiQwandaService.getSearchResults(searchJson, "Bearer " + serviceToken.getToken());

			JsonObject resultJson = null;

			try {
				resultJson = jsonb.fromJson(resultJsonStr, JsonObject.class);
				JsonArray result = resultJson.getJsonArray("codes");
				if (result == null) {
					log.error("Could ot fetch JsonArray from 'codes' "+resultJson);
				}
				log.info("Fetched "+result.size()+" for " + searchBE.getCode() + ":" + resultJson);
				int size = result.size();
				for (int i = 0; i < size; i++) {
					String code = result.getString(i);
					BaseEntity be = null;
					log.info(" "+i+" of "+size+" Fetching be with code = "+code);
					try {
						be = fetchBaseEntityFromCache(code, serviceToken);
					} catch (Exception e) {
						log.error("Error in fetching baseentitys from search result code -> " + code+" error "+e.getLocalizedMessage());
					 if (i > 0) {
						 i--;
						 continue;
					 }
					}
//					System.out.println("code:" + code + ",index:" + (i+1) + "/" + size);
					if (be == null) {
						log.error("No Baseentity with code "+code+" in cache");
						continue;
					}
					be.setIndex(i);
					results.add(be);
				}

			} catch (Exception e1) {
				log.error("Error in fetching baseentitys from search result codes -> " + resultJsonStr+" error "+e1.getLocalizedMessage());
			}

		} catch (Exception e1) {
			e1.printStackTrace();
		}
		return results;
	}

	private GennyToken getToken(final String username, final String password) throws IOException {

		JsonObject keycloakResponseJson = getToken(baseKeycloakUrl, keycloakRealm, clientId, secret, username, password,
				null);
		String accessToken = keycloakResponseJson.getString("access_token");
		GennyToken token = new GennyToken(accessToken);
		return token;
	}

	public JsonObject getToken(String keycloakUrl, String realm, String clientId, String secret, String username,
			String password, String refreshToken) throws IOException {

		HashMap<String, String> postDataParams = new HashMap<>();
		postDataParams.put("Content-Type", "application/x-www-form-urlencoded");
		/* if we have a refresh token */
//		if(refreshToken != null) {
//
//			/* we decode it */
//			JsonObject decodedServiceToken = KeycloakUtils.getDecodedToken(refreshToken);
//
//			/* we get the expiry timestamp */
//			long expiryTime = decodedServiceToken.getLong("exp");
//
//			/* we get the current time */
//			long nowTime = LocalDateTime.now().atZone(TimeZone.getDefault().toZoneId()).toEpochSecond();
//
//			/* we calculate the differencr */ 
//			long duration = expiryTime - nowTime;
//
//			/* if the difference is negative it means the expiry time is less than the nowTime 
//				if the difference < 180000, it means the token will expire in 3 hours
//			*/
//			if(duration <= GennySettings.ACCESS_TOKEN_EXPIRY_LIMIT_SECONDS) {
//
//				/* if the refresh token is about to expire, we must re-generate a new one */
//				refreshToken = null;
//			}
//		}
		/*
		 * if we don't have a refresh token, we generate a new token using username and
		 * password
		 */
		if (refreshToken == null) {
			postDataParams.put("username", username);
			postDataParams.put("password", password);
			if (showValues) {
				log.info("using username " + username);
				log.info("using password " + password);
				log.info("using client_id " + clientId);
				log.info("using client_secret " + secret);
			}
			postDataParams.put("grant_type", "password");
		} else {
			postDataParams.put("refresh_token", refreshToken);
			postDataParams.put("grant_type", "refresh_token");
			if (showValues) {
				log.info("using refresh token");
				log.info(refreshToken);
			}
		}

		postDataParams.put("client_id", clientId);
		if (!StringUtils.isBlank(secret)) {
			postDataParams.put("client_secret", secret);
		}

		String requestURL = keycloakUrl + "/auth/realms/" + realm + "/protocol/openid-connect/token";

		String str = performPostCall(requestURL, postDataParams);

		if (showValues) {
			log.info("keycloak auth url = " + requestURL);
			log.info(username + " token= " + str);
		}

		JsonObject json = jsonb.fromJson(str, JsonObject.class);
		return json;

	}

	public static String performPostCall(String requestURL, HashMap<String, String> postDataParams) {

		URL url;
		String response = "";
		try {
			url = new URL(requestURL);

			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setReadTimeout(15000);
			conn.setConnectTimeout(15000);
			conn.setRequestMethod("POST");
			conn.setDoInput(true);
			conn.setDoOutput(true);

			OutputStream os = conn.getOutputStream();
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
			writer.write(getPostDataString(postDataParams));

			writer.flush();
			writer.close();
			os.close();
			int responseCode = conn.getResponseCode();

			if (responseCode == HttpsURLConnection.HTTP_OK) {
				String line;
				BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				while ((line = br.readLine()) != null) {
					response += line;
				}
			} else {
				response = "";

			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return response;
	}

	private static String getPostDataString(HashMap<String, String> params) throws UnsupportedEncodingException {
		StringBuilder result = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, String> entry : params.entrySet()) {
			if (first)
				first = false;
			else
				result.append("&");

			result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
			result.append("=");
			result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
		}

		return result.toString();
	}



}
