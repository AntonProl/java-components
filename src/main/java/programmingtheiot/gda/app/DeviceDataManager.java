/**
 * This class is part of the Programming the Internet of Things project.
 * 
 * It is provided as a simple shell to guide the student and assist with
 * implementation for the Programming the Internet of Things exercises,
 * and designed to be modified by the student as needed.
 */ 

package programmingtheiot.gda.app;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import programmingtheiot.common.ConfigConst;
import programmingtheiot.common.ConfigUtil;
import programmingtheiot.common.IActuatorDataListener;
import programmingtheiot.common.IDataMessageListener;
import programmingtheiot.common.ResourceNameEnum;

import programmingtheiot.data.ActuatorData;
import programmingtheiot.data.BaseIotData;
import programmingtheiot.data.DataUtil;
import programmingtheiot.data.SensorData;
import programmingtheiot.data.SystemPerformanceData;
import programmingtheiot.data.SystemStateData;

import programmingtheiot.gda.connection.CloudClientConnector;
import programmingtheiot.gda.connection.CoapServerGateway;
import programmingtheiot.gda.connection.IPersistenceClient;
import programmingtheiot.gda.connection.IPubSubClient;
import programmingtheiot.gda.connection.IRequestResponseClient;
import programmingtheiot.gda.connection.MqttClientConnector;
import programmingtheiot.gda.connection.RedisPersistenceAdapter;
import programmingtheiot.gda.connection.SmtpClientConnector;
import programmingtheiot.gda.system.SystemPerformanceManager;
import programmingtheiot.gda.connection.ICloudClient;

/**
 * Shell representation of class for student implementation.
 *
 */
public class DeviceDataManager implements IDataMessageListener
{
	// static
	
	private static final Logger _Logger =
		Logger.getLogger(DeviceDataManager.class.getName());
	
	// private var's
	
	private boolean enableMqttClient = true;
	private boolean enableCoapServer = true;
	private boolean enableCloudClient = false;
	private boolean enableSmtpClient = false;
	private boolean enablePersistenceClient = false;
	private boolean enableSystemPerf = false;
	
	private IActuatorDataListener actuatorDataListener = null;
	private IPubSubClient mqttClient = null;
	private IPubSubClient cloudClient = null;
	private IPersistenceClient persistenceClient = null;
	private IRequestResponseClient smtpClient = null;
	private CoapServerGateway coapServer = null;
	private SystemPerformanceManager sysPerfMgr = null;

	private ActuatorData latestHumidifierActuatorData = null;
	private ActuatorData latestHumidifierActuatorResponse = null;
	private SensorData latestHumiditySensorData = null;
	private OffsetDateTime latestHumiditySensorTimeStamp = null;

	private boolean handleHumidityChangeOnDevice = false; // opcional
	private int lastKnownHumidifierCommand = ConfigConst.OFF_COMMAND;

	// TODO: Cargar estos desde PiotConfig.props
	private long humidityMaxTimePastThreshold = 300; // segundos
	private float nominalHumiditySetting = 40.0f;
	private float triggerHumidifierFloor = 30.0f;
	private float triggerHumidifierCeiling = 50.0f;
	
	// constructors
	
	public DeviceDataManager()
	{
		super();
		
		ConfigUtil configUtil = ConfigUtil.getInstance();

		this.enableMqttClient =
			configUtil.getBoolean(
				ConfigConst.GATEWAY_DEVICE, ConfigConst.ENABLE_MQTT_CLIENT_KEY);

		this.enableCoapServer =
			configUtil.getBoolean(
				ConfigConst.GATEWAY_DEVICE, ConfigConst.ENABLE_COAP_SERVER_KEY);

		this.enableCloudClient =
			configUtil.getBoolean(
				ConfigConst.GATEWAY_DEVICE, ConfigConst.ENABLE_CLOUD_CLIENT_KEY);

		this.enablePersistenceClient =
			configUtil.getBoolean(
				ConfigConst.GATEWAY_DEVICE, ConfigConst.ENABLE_PERSISTENCE_CLIENT_KEY);

		
		// analizar reglas de configuración para eventos de actuación local

		// TODO: añadir estos a ConfigConst
		this.handleHumidityChangeOnDevice = configUtil.getBoolean(
				ConfigConst.GATEWAY_DEVICE, "handleHumidityChangeOnDevice"); // Nota del traductor: La clave
																				// "handleHumidityChangeOnDevice"
																				// debería estar en ConfigConst o ser
																				// una cadena literal.

		this.humidityMaxTimePastThreshold = configUtil.getInteger(
				ConfigConst.GATEWAY_DEVICE, "humidityMaxTimePastThreshold"); // Nota del traductor: Clave como arriba.

		this.nominalHumiditySetting = configUtil.getFloat(
				ConfigConst.GATEWAY_DEVICE, "nominalHumiditySetting"); // Nota del traductor: Clave como arriba.

		this.triggerHumidifierFloor = configUtil.getFloat(
				ConfigConst.GATEWAY_DEVICE, "triggerHumidifierFloor"); // Nota del traductor: Clave como arriba.

		this.triggerHumidifierCeiling = configUtil.getFloat(
				ConfigConst.GATEWAY_DEVICE, "triggerHumidifierCeiling"); // Nota del traductor: Clave como arriba.

		// TODO: validación básica para la temporización - añadir otros validadores para
		// los valores restantes
		if (this.humidityMaxTimePastThreshold < 10 || this.humidityMaxTimePastThreshold > 7200) {
			this.humidityMaxTimePastThreshold = 300;
		}

		initConnections();
	}
	
	public DeviceDataManager(
		boolean enableMqttClient,
		boolean enableCoapClient,
		boolean enableCloudClient,
		boolean enableSmtpClient,
		boolean enablePersistenceClient)
	{
		super();
		
		initConnections();
	}
	
	
	// public methods
	
	@Override
	public boolean handleActuatorCommandResponse(ResourceNameEnum resourceName, ActuatorData data)
	{
		if (data != null) {
			_Logger.info("Handling actuator response: " + data.getName());
	
			// Esta siguiente llamada es opcional por ahora
			//this.handleIncomingDataAnalysis(resourceName, data);
	
			if (data.hasError()) {
				_Logger.warning("Error flag set for ActuatorData instance.");
			}
	
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean handleActuatorCommandRequest(ResourceNameEnum resourceName, ActuatorData data)
	{
		if (data != null) {
		// NOTA: Siéntete libre de actualizar este mensaje de registro para depuración y monitoreo
		_Logger.log(
			Level.FINE,
			"Solicitud de actuador recibida: {0}. Mensaje: {1}",
			new Object[] {resourceName.getResourceName(), Integer.valueOf((data.getCommand()))});

		if (data.hasError()) {
			_Logger.warning("Indicador de error activado para la instancia de ActuatorData.");
		}

		// TODO: recuperar esto del archivo de configuración
		int qos = ConfigConst.DEFAULT_QOS;

		// TODO: quizás quieras implementar alguna lógica de análisis aquí o
		// en un método separado para determinar la mejor manera de manejar
		// ActuatorData entrante antes de llamar a this.sendActuatorCommandtoCda()

		// Recuerda que este método privado se implementó en el Módulo de Laboratorio 10
		// Consulta PIOT-GDA-10-003 para más detalles
		this.sendActuatorCommandtoCda(resourceName, data);

		return true;
	} else {
		return false;
	}
	}

	@Override
public boolean handleIncomingMessage(ResourceNameEnum resourceName, String msg)
{
	if (resourceName != null && msg != null) {
		try {
			if (resourceName == ResourceNameEnum.CDA_ACTUATOR_CMD_RESOURCE) {
				_Logger.info("Manejando mensaje ActuatorData entrante: " + msg);

				// NOTA: puede parecer un desperdicio convertir a ActuatorData y viceversa mientras
				// los datos JSON ya están disponibles; sin embargo, esto proporciona un esquema de
				// validación para asegurar que los datos son realmente una instancia 'ActuatorData'
				// antes de enviarlos al CDA
				ActuatorData ad = DataUtil.getInstance().jsonToActuatorData(msg);
				String jsonData = DataUtil.getInstance().actuatorDataToJson(ad);

				if (this.mqttClient != null) {
					// TODO: recuperar el nivel de QoS del archivo de configuración
					_Logger.fine("Publicando datos al broker MQTT: " + jsonData);
					return this.mqttClient.publishMessage(resourceName, jsonData, 0);
				}

				// TODO: Si el GDA está alojando un servidor CoAP (o un cliente CoAP que
				// se conectará al servidor CoAP del CDA), puedes añadir esa lógica aquí
				// en lugar del cliente MQTT o además

			} else {
				_Logger.warning("Fallo al analizar mensaje entrante. Tipo desconocido: " + msg);

				return false;
			}
		} catch (Exception e) {
			_Logger.log(Level.WARNING, "Fallo al procesar mensaje entrante para el recurso: " + resourceName, e);
		}
	} else {
		_Logger.warning("Mensaje entrante no tiene datos. Ignorando para el recurso: " + resourceName);
	}

	return false;
}
	

@Override
public boolean handleSensorMessage(ResourceNameEnum resourceName, SensorData data)
{
	if (data != null) {
		_Logger.fine("Manejando mensaje del sensor: " + data.getName());

		if (data.hasError()) {
			_Logger.warning("Indicador de error activado para la instancia de SensorData.");
		}

		String jsonData = DataUtil.getInstance().sensorDataToJson(data);

		_Logger.fine("JSON [SensorData] -> " + jsonData);

		// TODO: recuperar esto del archivo de configuración
		int qos = ConfigConst.DEFAULT_QOS;

		// NOTA: Tu código puede no tener una referencia persistenceClient o
		// un booleano enablePersistenceClient
		if (this.enablePersistenceClient && this.persistenceClient != null) {
			this.persistenceClient.storeData(resourceName.getResourceName(), qos, data);
		}

		this.handleIncomingDataAnalysis(resourceName, data);

		this.handleUpstreamTransmission(resourceName, jsonData, qos);

		return true;
	} else {
		return false;
	}
}
	
	@Override
	public boolean handleSystemPerformanceMessage(ResourceNameEnum resourceName, SystemPerformanceData data)
	{
		if (data != null) {
		_Logger.info("Manejando mensaje de rendimiento del sistema: " + data.getName());

		if (data.hasError()) {
			_Logger.warning("Indicador de error activado para la instancia de SystemPerformanceData.");
		}

		// TODO: recuperar esto del archivo de configuración
		int qos = ConfigConst.DEFAULT_QOS;

		// NOTA: Quizás quieras persistir tu SystemPerformanceData aquí

		// NOTA: Quizás también quieras analizar el SystemPerformanceData aquí

		String jsonData = DataUtil.getInstance().systemPerformanceDataToJson(data);
		this.handleUpstreamTransmission(resourceName, jsonData, qos);

		return true;
	} else {
		return false;
	}
	}
	
	public void setActuatorDataListener(String name, IActuatorDataListener listener)
	{
		if (listener != null) {
			// Por ahora, solo ignora 'name'. Si necesitas más de un listener,
			// puedes usar 'name' para crear un mapa de instancias de listeners.
			this.actuatorDataListener = listener;
		}
	}
	
	public void startManager()
	{
		if (this.mqttClient != null) {
			if (this.mqttClient.connectClient()) {
				_Logger.info("Cliente MQTT conectado exitosamente al broker.");
	
				// agregar suscripciones necesarias
	
				// TODO: leer esto del archivo de configuración
				int qos = ConfigConst.DEFAULT_QOS;
	
				// TODO: verificar el valor de retorno de cada uno y tomar acción apropiada
	
				// NOTA IMPORTANTE: Las llamadas al método 'subscribeToTopic()' mostradas
				// abajo se moverán a MqttClientConnector.connectComplete()
				// en el Módulo de Laboratorio 10. Por ahora, pueden permanecer aquí.
				//this.mqttClient.subscribeToTopic(ResourceNameEnum.GDA_MGMT_STATUS_MSG_RESOURCE, qos);
				//this.mqttClient.subscribeToTopic(ResourceNameEnum.CDA_ACTUATOR_RESPONSE_RESOURCE, qos);
				//this.mqttClient.subscribeToTopic(ResourceNameEnum.CDA_SENSOR_MSG_RESOURCE, qos);
				//this.mqttClient.subscribeToTopic(ResourceNameEnum.CDA_SYSTEM_PERF_MSG_RESOURCE, qos);
			} else {
				_Logger.severe("No se pudo conectar el cliente MQTT al broker.");
	
				// TODO: tomar acción apropiada
			}
		}
		if (this.sysPerfMgr != null) {
			this.sysPerfMgr.startManager();
		}
		if (this.enableCoapServer && this.coapServer != null) {
			if (this.coapServer.startServer()) {
				_Logger.info("Servidor CoAP iniciado.");
			} else {
				_Logger.severe("Error al iniciar el servidor CoAP. Revisa el archivo de registro.");
			}
		}
	}
	
	public void stopManager()
	{
		if (this.sysPerfMgr != null) {
			this.sysPerfMgr.stopManager();
		}
		if (this.mqttClient != null) {
			// agregar des-suscripciones necesarias
	
			// TODO: verificar el valor de retorno de cada uno y tomar acción apropiada
	
			// NOTA: Las llamadas a unsubscribeFromTopic() deben coincidir con
			// las llamadas a subscribeToTopic() de startManager(). Además, la
			// lógica de des-suscripción puede moverse al método disconnectClient()
			// de MqttClientConnector ANTES de desconectarse del
			// broker MQTT.
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.GDA_MGMT_STATUS_MSG_RESOURCE);
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.CDA_ACTUATOR_RESPONSE_RESOURCE);
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.CDA_SENSOR_MSG_RESOURCE);
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.CDA_SYSTEM_PERF_MSG_RESOURCE);
	
			if (this.mqttClient.disconnectClient()) {
				_Logger.info("Cliente MQTT desconectado exitosamente del broker.");
			} else {
				_Logger.severe("Fallo al desconectar el cliente MQTT del broker.");
	
				// TODO: tomar acción apropiada
			}
		}
		if (this.enableCoapServer && this.coapServer != null) {
			if (this.coapServer.stopServer()) {
				_Logger.info("Servidor CoAP detenido.");
			} else {
				_Logger.severe("Error al detener el servidor CoAP. Revisa el archivo de registro.");
			}
		}
	}

	
	// private methods
	
	/**
	 * Initializes the enabled connections. This will NOT start them, but only create the
	 * instances that will be used in the {@link #startManager() and #stopManager()) methods.
	 * 
	 */
	private void initConnections() {
		ConfigUtil configUtil = ConfigUtil.getInstance();

		this.enableSystemPerf = configUtil.getBoolean(ConfigConst.GATEWAY_DEVICE, ConfigConst.ENABLE_SYSTEM_PERF_KEY);

		if (this.enableSystemPerf) {
			this.sysPerfMgr = new SystemPerformanceManager();
			this.sysPerfMgr.setDataMessageListener(this);
		}

		if (this.enableMqttClient) {
			// TODO: implementar esto en el Módulo de Laboratorio 7
			this.mqttClient = new MqttClientConnector();

			// NOTA: La siguiente línea no es técnicamente necesaria hasta el Módulo de
			// Laboratorio 10
			this.mqttClient.setDataMessageListener(this);
		}

		if (this.enableCoapServer) {
			// TODO: implementar esto en el Módulo de Laboratorio 8
			this.coapServer = new CoapServerGateway(this);
		}

		if (this.enableCloudClient) {
			// TODO: implementar esto en el Módulo de Laboratorio 10
		}

		if (this.enablePersistenceClient) {
			// TODO: implementar esto como un ejercicio opcional en el Módulo de Laboratorio
			// 5
		}
	}

	private void handleIncomingDataAnalysis(ResourceNameEnum resource, ActuatorData data) {
		_Logger.info("Analizando datos del actuador entrantes: " + data.getName());

		if (data.isResponseFlagEnabled()) {
			// TODO: implementar esto
		} else {
			if (this.actuatorDataListener != null) {
				this.actuatorDataListener.onActuatorDataUpdate(data);
			}
		}
	}

	private void handleUpstreamTransmission(ResourceNameEnum resource, String jsonData, int qos) {
		_Logger.fine("Enviando datos JSON al servicio en la nube: " + resource);

		if (this.enableCloudClient && this.cloudClient != null) {
			boolean success = this.cloudClient.sendEdgeDataToCloud(resource, jsonData, qos);
			if (success) {
				_Logger.fine("Datos JSON enviados ascendentemente a CSP.");
			} else {
				_Logger.warning("Fallo al enviar datos JSON al servicio en la nube.");
			}
		} else {
			_Logger.fine("CloudClient no está habilitado o no está inicializado. No se envían datos ascendentemente.");
		}
	}

	private void handleIncomingDataAnalysis(ResourceNameEnum resource, SensorData data) {
		// verificar ya sea recurso o SensorData por tipo
		if (data.getTypeID() == ConfigConst.HUMIDITY_SENSOR_TYPE) {
			handleHumiditySensorAnalysis(resource, data);
		}
	}

	private void handleHumiditySensorAnalysis(ResourceNameEnum resource, SensorData data) {
		//
		// NOTA: EJEMPLO DE CÓDIGO INCOMPLETO y MUY BÁSICO. No pretende proporcionar una
		// solución.
		//

		_Logger.fine("Analizando datos de humedad del CDA: " + data.getLocationID() + ". Valor: " + data.getValue());

		boolean isLow = data.getValue() < this.triggerHumidifierFloor;
		boolean isHigh = data.getValue() > this.triggerHumidifierCeiling;

		if (isLow || isHigh) {
			_Logger.fine("Datos de humedad del CDA exceden el rango nominal.");

			if (this.latestHumiditySensorData == null) {
				// establecer propiedades y luego salir - nada más que hacer hasta la siguiente
				// muestra
				this.latestHumiditySensorData = data;
				this.latestHumiditySensorTimeStamp = getDateTimeFromData(data);

				_Logger.fine(
						"Iniciando temporizador de excepción nominal de humedad. Esperando segundos: " +
								this.humidityMaxTimePastThreshold);

				return;
			} else {
				OffsetDateTime curHumiditySensorTimeStamp = getDateTimeFromData(data);

				long diffSeconds = ChronoUnit.SECONDS.between(
						this.latestHumiditySensorTimeStamp, curHumiditySensorTimeStamp);

				_Logger.fine("Verificando delta de tiempo de excepción de valor de Humedad: " + diffSeconds);

				if (diffSeconds >= this.humidityMaxTimePastThreshold) {
					ActuatorData ad = new ActuatorData();
					ad.setName(ConfigConst.HUMIDIFIER_ACTUATOR_NAME);
					ad.setLocationID(data.getLocationID());
					ad.setTypeID(ConfigConst.HUMIDIFIER_ACTUATOR_TYPE);
					ad.setValue(this.nominalHumiditySetting);

					if (isLow) {
						ad.setCommand(ConfigConst.ON_COMMAND);
					} else if (isHigh) {
						ad.setCommand(ConfigConst.OFF_COMMAND);
					}

					_Logger.info(
							"Valor excepcional de humedad alcanzado. Enviando evento de actuación al CDA: " +
									ad);

					this.lastKnownHumidifierCommand = ad.getCommand();
					sendActuatorCommandtoCda(ResourceNameEnum.CDA_ACTUATOR_CMD_RESOURCE, ad);

					// establecer ActuatorData y reiniciar SensorData (y marca de tiempo)
					this.latestHumidifierActuatorData = ad;
					this.latestHumiditySensorData = null;
					this.latestHumiditySensorTimeStamp = null;
				}
			}
		} else if (this.lastKnownHumidifierCommand == ConfigConst.ON_COMMAND) {
			// verificar si necesitamos apagar el humidificador
			if (this.latestHumidifierActuatorData != null) {
				// verificar el valor - si el humidificador está encendido, pero aún no en
				// nominal, mantenerlo encendido
				if (this.latestHumidifierActuatorData.getValue() >= this.nominalHumiditySetting) {
					this.latestHumidifierActuatorData.setCommand(ConfigConst.OFF_COMMAND);

					_Logger.info(
							"Valor nominal de humedad alcanzado. Enviando evento de actuación APAGADO al CDA: " +
									this.latestHumidifierActuatorData);

					sendActuatorCommandtoCda(
							ResourceNameEnum.CDA_ACTUATOR_CMD_RESOURCE, this.latestHumidifierActuatorData);

					// reiniciar ActuatorData y SensorData (y marca de tiempo)
					this.lastKnownHumidifierCommand = this.latestHumidifierActuatorData.getCommand();
					this.latestHumidifierActuatorData = null;
					this.latestHumiditySensorData = null;
					this.latestHumiditySensorTimeStamp = null;
				} else {
					_Logger.fine("Humidificador aún encendido. Aún no en niveles nominales (OK).");
				}
			} else {
				// no debería suceder, a menos que alguna otra lógica
				// anule la instancia ActuatorData de ámbito de clase
				_Logger.warning(
						"ERROR: ActuatorData para humidificador es nulo (no debería serlo). No se puede enviar comando.");
			}
		}
	}

	private void sendActuatorCommandtoCda(ResourceNameEnum resource, ActuatorData data) {
		// NOTA: Así es como se pasará un comando ActuatorData al CDA
		// cuando el GDA proporciona el servidor CoAP y aloja el recurso
		// ActuatorData apropiado. Normalmente se usará cuando el cliente OBSERVE
		// (el CDA, asumiendo que el GDA es el servidor y el CDA es el cliente)
		// ha enviado una solicitud GET OBSERVE al recurso ActuatorData.
		if (this.actuatorDataListener != null) {
			this.actuatorDataListener.onActuatorDataUpdate(data);
		}

		// NOTA: Así es como se pasará un comando ActuatorData al CDA
		// cuando se usa MQTT para comunicar entre el GDA y el CDA
		if (this.enableMqttClient && this.mqttClient != null) {
			String jsonData = DataUtil.getInstance().actuatorDataToJson(data);

			if (this.mqttClient.publishMessage(resource, jsonData, ConfigConst.DEFAULT_QOS)) {
				_Logger.info(
						"Comando ActuatorData publicado desde GDA a CDA: " + data.getCommand());
			} else {
				_Logger.warning(
						"Fallo al publicar comando ActuatorData desde GDA a CDA: " + data.getCommand());
			}
		}
	}

	private OffsetDateTime getDateTimeFromData(BaseIotData data) {
		OffsetDateTime odt = null;

		try {
			odt = OffsetDateTime.parse(data.getTimeStamp());
		} catch (Exception e) {
			_Logger.warning(
					"Fallo al extraer marca de tiempo ISO 8601 de datos IoT. Usando hora actual local.");

			// TODO: esto no será preciso, pero debería estar razonablemente cerca, ya que
			// el CDA
			// muy probablemente habrá enviado recientemente los datos al GDA
			odt = OffsetDateTime.now();
		}

		return odt;
	}

	
}

