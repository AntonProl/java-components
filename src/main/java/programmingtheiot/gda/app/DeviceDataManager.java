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

public class DeviceDataManager implements IDataMessageListener
{
	private static final Logger _Logger =
		Logger.getLogger(DeviceDataManager.class.getName());

	private boolean enableMqttClient = true;
	private boolean enableCoapServer = true;
	private boolean enableCloudClient = false;
	private boolean enableSmtpClient = false;
	private boolean enablePersistenceClient = false;
	private boolean enableSystemPerf = false;

	private IActuatorDataListener actuatorDataListener = null;
	private IPubSubClient mqttClient = null;
	private ICloudClient cloudClient = null;
	private IPersistenceClient persistenceClient = null;
	private IRequestResponseClient smtpClient = null;
	private CoapServerGateway coapServer = null;
	private SystemPerformanceManager sysPerfMgr = null;

	private ActuatorData latestHumidifierActuatorData = null;
	private ActuatorData latestHumidifierActuatorResponse = null;
	private SensorData latestHumiditySensorData = null;
	private OffsetDateTime latestHumiditySensorTimeStamp = null;

	private boolean handleHumidityChangeOnDevice = false;
	private int lastKnownHumidifierCommand = ConfigConst.OFF_COMMAND;

	private long humidityMaxTimePastThreshold = 300;
	private float nominalHumiditySetting = 40.0f;
	private float triggerHumidifierFloor = 30.0f;
	private float triggerHumidifierCeiling = 50.0f;

	// Variables de instancia para la lógica del purificador (si son necesarias para
	// estado o temporización)
	private boolean airPurifierIsOn = false; // Para saber el estado actual asumido
	private OffsetDateTime lastAirQualityBadTimestamp = null;
	private long airQualityActionThresholdMillis = 30000; // Ej: actuar si la calidad es mala por 30 seg

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

		this.enableSystemPerf =
			configUtil.getBoolean(
				ConfigConst.GATEWAY_DEVICE, ConfigConst.ENABLE_SYSTEM_PERF_KEY);

		this.handleHumidityChangeOnDevice = configUtil.getBoolean(
			ConfigConst.GATEWAY_DEVICE, "handleHumidityChangeOnDevice");

		this.humidityMaxTimePastThreshold = configUtil.getInteger(
			ConfigConst.GATEWAY_DEVICE, "humidityMaxTimePastThreshold");

		this.nominalHumiditySetting = configUtil.getFloat(
			ConfigConst.GATEWAY_DEVICE, "nominalHumiditySetting");

		this.triggerHumidifierFloor = configUtil.getFloat(
			ConfigConst.GATEWAY_DEVICE, "triggerHumidifierFloor");

		this.triggerHumidifierCeiling = configUtil.getFloat(
			ConfigConst.GATEWAY_DEVICE, "triggerHumidifierCeiling");

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
		this.enableMqttClient = enableMqttClient;
		this.enableCoapServer = enableCoapClient;
		this.enableCloudClient = enableCloudClient;
		this.enableSmtpClient = enableSmtpClient;
		this.enablePersistenceClient = enablePersistenceClient;
		initConnections();
	}

	@Override
	public boolean handleActuatorCommandResponse(ResourceNameEnum resourceName, ActuatorData data)
	{
		if (data != null) {
			_Logger.info("Handling actuator response: " + data.getName());
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
			_Logger.log(
				Level.FINE,
				"Solicitud de actuador recibida: {0}. Mensaje: {1}",
				new Object[] {resourceName.getResourceName(), Integer.valueOf((data.getCommand()))});
			if (data.hasError()) {
				_Logger.warning("Indicador de error activado para la instancia de ActuatorData.");
			}
			int qos = ConfigConst.DEFAULT_QOS;
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
					ActuatorData ad = DataUtil.getInstance().jsonToActuatorData(msg);
					String jsonData = DataUtil.getInstance().actuatorDataToJson(ad);
					if (this.mqttClient != null) {
						_Logger.fine("Publicando datos al broker MQTT: " + jsonData);
						return this.mqttClient.publishMessage(resourceName, jsonData, 0);
					}
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
			int qos = ConfigConst.DEFAULT_QOS;
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
			int qos = ConfigConst.DEFAULT_QOS;
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
			this.actuatorDataListener = listener;
		}
	}

	public void startManager()
	{
		if (this.mqttClient != null) {
			if (this.mqttClient.connectClient()) {
				_Logger.info("Cliente MQTT conectado exitosamente al broker.");
				int qos = ConfigConst.DEFAULT_QOS;
				// Suscripciones aquí si es necesario
			} else {
				_Logger.severe("No se pudo conectar el cliente MQTT al broker.");
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
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.GDA_MGMT_STATUS_MSG_RESOURCE);
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.CDA_ACTUATOR_RESPONSE_RESOURCE);
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.CDA_SENSOR_MSG_RESOURCE);
			this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.CDA_SYSTEM_PERF_MSG_RESOURCE);
			if (this.mqttClient.disconnectClient()) {
				_Logger.info("Cliente MQTT desconectado exitosamente del broker.");
			} else {
				_Logger.severe("Fallo al desconectar el cliente MQTT del broker.");
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

	private void initConnections() {
		ConfigUtil configUtil = ConfigUtil.getInstance();

		this.enableSystemPerf = configUtil.getBoolean(ConfigConst.GATEWAY_DEVICE, ConfigConst.ENABLE_SYSTEM_PERF_KEY);

		if (this.enableSystemPerf) {
			this.sysPerfMgr = new SystemPerformanceManager();
			this.sysPerfMgr.setDataMessageListener(this);
		}

		if (this.enableMqttClient) {
			this.mqttClient = new MqttClientConnector();
			this.mqttClient.setDataMessageListener(this);
		}

		if (this.enableCoapServer) {
			this.coapServer = new CoapServerGateway(this);
		}

		if (this.enableCloudClient) {
			this.cloudClient = new CloudClientConnector();
			this.cloudClient.setDataMessageListener(this);
		}

		if (this.enablePersistenceClient) {
			// Implementar si es necesario
		}
	}

	private void handleIncomingDataAnalysis(ResourceNameEnum resource, ActuatorData data) {
		_Logger.info("Analizando datos del actuador entrantes: " + data.getName());
		if (data.isResponseFlagEnabled()) {
			// Implementar si es necesario
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
		if (data.getTypeID() == ConfigConst.HUMIDITY_SENSOR_TYPE) {
			handleHumiditySensorAnalysis(resource, data);
		}
		if (data.getTypeID() == ConfigConst.AIR_QUALITY_SENSOR_TYPE) {
			handleAirQualitySensorAnalysis(resource, data);
		}
	}

	private void handleHumiditySensorAnalysis(ResourceNameEnum resource, SensorData data) {
		_Logger.fine("Analizando datos de humedad del CDA: " + data.getLocationID() + ". Valor: " + data.getValue());
		boolean isLow = data.getValue() < this.triggerHumidifierFloor;
		boolean isHigh = data.getValue() > this.triggerHumidifierCeiling;

		if (isLow || isHigh) {
			_Logger.fine("Datos de humedad del CDA exceden el rango nominal.");
			if (this.latestHumiditySensorData == null) {
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
					this.latestHumidifierActuatorData = ad;
					this.latestHumiditySensorData = null;
					this.latestHumiditySensorTimeStamp = null;
				}
			}
		} else if (this.lastKnownHumidifierCommand == ConfigConst.ON_COMMAND) {
			if (this.latestHumidifierActuatorData != null) {
				if (this.latestHumidifierActuatorData.getValue() >= this.nominalHumiditySetting) {
					this.latestHumidifierActuatorData.setCommand(ConfigConst.OFF_COMMAND);
					_Logger.info(
							"Valor nominal de humedad alcanzado. Enviando evento de actuación APAGADO al CDA: " +
									this.latestHumidifierActuatorData);
					sendActuatorCommandtoCda(
							ResourceNameEnum.CDA_ACTUATOR_CMD_RESOURCE, this.latestHumidifierActuatorData);
					this.lastKnownHumidifierCommand = this.latestHumidifierActuatorData.getCommand();
					this.latestHumidifierActuatorData = null;
					this.latestHumiditySensorData = null;
					this.latestHumiditySensorTimeStamp = null;
				} else {
					_Logger.fine("Humidificador aún encendido. Aún no en niveles nominales (OK).");
				}
			} else {
				_Logger.warning(
						"ERROR: ActuatorData para humidificador es nulo (no debería serlo). No se puede enviar comando.");
			}
		}
	}

	private void sendActuatorCommandtoCda(ResourceNameEnum resource, ActuatorData data) {
		if (this.actuatorDataListener != null) {
			this.actuatorDataListener.onActuatorDataUpdate(data);
		}
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
			odt = OffsetDateTime.now();
		}
		return odt;
	}


	private void handleAirQualitySensorAnalysis(ResourceNameEnum resource, SensorData data) {
		_Logger.info(
				"Analizando datos del Sensor de Calidad del Aire: ID=" + data.getName() + " Valor=" + data.getValue());
		// Implementa tu lógica de análisis aquí:
		// - ¿Cruza algún umbral?
		// - ¿Necesita almacenarse? (ya se hace en handleSensorMessage)
		// - ¿Necesita activar alguna acción en el GDA o enviar un comando a otro
		// actuador?
		if (data.getValue() > 200) { // Ejemplo de umbral
			_Logger.warning("¡ALERTA: Calidad del aire MALA! Valor: " + data.getValue());
			// Podrías, por ejemplo, enviar un comando para encender un purificador de aire
			// (otro actuador)
			// o enviar una notificación por correo.
		}
	}

	

	private void sendAirPurifierCommand(int command, float triggerValue) {
		ActuatorData airPurifierCmd = new ActuatorData();
		airPurifierCmd.setName(ConfigConst.AIR_PURIFIER_ACTUATOR_NAME);

		// Obtener el ID del CDA al que enviar el comando. Podría ser una config o
		// derivado.
		String cdaID = ConfigUtil.getInstance().getProperty(
				ConfigConst.CONSTRAINED_DEVICE, ConfigConst.DEVICE_LOCATION_ID_KEY, "constraineddevice001");
		airPurifierCmd.setLocationID(cdaID);

		airPurifierCmd.setTypeID(ConfigConst.AIR_PURIFIER_ACTUATOR_TYPE);
		airPurifierCmd.setCommand(command);
		airPurifierCmd.setValue(triggerValue); // Opcional: enviar el valor que disparó el comando

		String action = (command == ConfigConst.ON_COMMAND) ? "ENCENDIDO" : "APAGADO";
		airPurifierCmd.setStateData("Purificador de Aire puesto a " + action + " debido a ICA=" + triggerValue);

		_Logger.info("Enviando comando al CDA para el Purificador de Aire: " + action);
		sendActuatorCommandtoCda(ResourceNameEnum.CDA_ACTUATOR_CMD_RESOURCE, airPurifierCmd);
	}
}
