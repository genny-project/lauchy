package life.genny.lauchy.streams;

import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.persistence.EntityManager;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

import life.genny.lauchy.live.data.InternalProducer;
import life.genny.qwandaq.models.GennyToken;
import life.genny.qwandaq.Answer;
import life.genny.qwandaq.attribute.Attribute;
import life.genny.qwandaq.datatype.DataType;
import life.genny.qwandaq.entity.BaseEntity;
import life.genny.qwandaq.message.QDataAnswerMessage;
import life.genny.qwandaq.message.QDataBaseEntityMessage;
import life.genny.qwandaq.validation.Validation;
import life.genny.qwandaq.data.GennyCache;
import life.genny.qwandaq.utils.BaseEntityUtils;
import life.genny.qwandaq.utils.CacheUtils;
import life.genny.qwandaq.utils.DatabaseUtils;
import life.genny.qwandaq.utils.DefUtils;
import life.genny.qwandaq.utils.KeycloakUtils;
import life.genny.qwandaq.utils.QwandaUtils;

@ApplicationScoped
public class TopologyProducer {

	private static final Logger log = Logger.getLogger(TopologyProducer.class);

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

	@Inject
	InternalProducer producer;

	@Inject
	EntityManager entityManager;

	@Inject
	GennyCache cache;

	GennyToken serviceToken;

	BaseEntityUtils beUtils;

	Jsonb jsonb = JsonbBuilder.create();

	static String DATA_TOPIC = "data";
	static String WEBDATA_TOPIC = "webdata";

    void onStart(@Observes StartupEvent ev) {

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

		// Fetch our service token
		serviceToken = new KeycloakUtils().getToken(baseKeycloakUrl, keycloakRealm, clientId, secret, serviceUsername, servicePassword, null);

		// Init Utility Objects
		beUtils = new BaseEntityUtils(serviceToken);

		// Establish connection to DB and cache, and init utilities
		DatabaseUtils.init(entityManager);
		CacheUtils.init(cache);
		QwandaUtils.init(serviceToken);
		DefUtils.init(beUtils);

		log.info("[*] Finished Topology Startup!");
    }

	@Produces
	public Topology buildTopology() {

		// Read the input Kafka topic into a KStream instance.
		StreamsBuilder builder = new StreamsBuilder();
		builder
			.stream("data", Consumed.with(Serdes.String(), Serdes.String()))
			.mapValues((k, v) -> tidy(v))
			.filter((k, v) -> validate(v))
			.peek((k, v) -> log.info("Forwarding valid message: " + v))
			.to("valid_data", Produced.with(Serdes.String(), Serdes.String()));

		return builder.build();
	}

	/**
	* Helper function to tidy some values
	*
	* @param data
	* @return
	 */
	public String tidy(String data) {

		return data.replaceAll("Adamm", "Adam");
	}

	/**
	* Function for validating a data message.
	*
	* @param data
	* @return
	 */
	public Boolean validate(String data) {

		Boolean valid = true;
		String uuid = null;
		GennyToken userToken = null;
		
		if (data != null && !data.contains("Adaam")) {
			try {
				JsonObject json = jsonb.fromJson(data, JsonObject.class);

				String msgType = json.getString("msg_type");
				String msgDataType = json.getString("data_type");

				if ("DATA_MSG".equals(msgType) && ("Answer".equals(msgDataType))) {

					userToken = new GennyToken(json.getString("token"));

					JsonArray items = json.getJsonArray("items");
					log.info(userToken);
					QDataAnswerMessage answerMsg = jsonb.fromJson(data, QDataAnswerMessage.class);

					if (!userToken.getToken().equals(answerMsg.getToken())) {
						log.error("Message Token and userToken DO NOT Match for " + userToken.getEmail());
						valid = false;
					}

					for (Answer answer : answerMsg.getItems()) {

						// TODO, check questionCode by fetching from questions 5
						// TODO check askID by fetching from Tasks

						if (!(userToken.getUserCode()).equals(answer.getSourceCode())) {
							valid = false;
						} else {
							// check source code exists
							BaseEntity sourceBe = null;

							sourceBe = beUtils.getBaseEntityByCode(answer.getSourceCode());

							log.info("Source = " + sourceBe.getCode() + ":" + sourceBe.getName());
							if (sourceBe != null) {
								// Check Target exist
								BaseEntity targetBe = beUtils.getBaseEntityByCode(answer.getTargetCode());
								if (targetBe != null) {

									BaseEntity defBe = DefUtils.getDEF(targetBe);
									// check attribute code is allowed by targetDEF
									if (defBe.containsEntityAttribute("ATT_" + answer.getAttributeCode())) {
										// Now validate values
										Attribute attribute = QwandaUtils.getAttribute(answer.getAttributeCode());
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

	/**
	* Helper function for checking ABN validity.
	*
	* Thanks to "Joker" from stackOverflow - https://stackoverflow.com/users/3949925/joker
	*
	* @param abnCode	The ABN to check
	* @return			{@link Boolean}: ABN is valid
	 */
	public static Boolean isValidABN(final String abnCode) {

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

	/**
	* Helper function to check if a credit card number is valid
	*
	* @param creditCardNumber	Credit card number to check
	* @return			{@link Boolean}: Number is valid
	 */
	public static boolean isValidCreditCard(String creditCardNumber) {
		int sum = 0;
		boolean alternate = false;
		for (int i = creditCardNumber.length() - 1; i >= 0; i--) {
			int n = Integer.parseInt(creditCardNumber.substring(i, i + 1));
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

}
