/**
 * This class is part of the Programming the Internet of Things project.
 * 
 * It is provided as a simple shell to guide the student and assist with
 * implementation for the Programming the Internet of Things exercises,
 * and designed to be modified by the student as needed.
 */

package programmingtheiot.gda.connection;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener; // Necesaria para la clase interna
import org.eclipse.paho.client.mqttv3.MqttMessage;      // Necesaria para la clase interna

import programmingtheiot.common.ConfigConst;
import programmingtheiot.common.ConfigUtil;
import programmingtheiot.common.IDataMessageListener;
import programmingtheiot.common.ResourceNameEnum;
import programmingtheiot.data.ActuatorData;
import programmingtheiot.data.DataUtil;
import programmingtheiot.data.SensorData;
import programmingtheiot.data.SystemPerformanceData;

/**
 * Shell representation of class for student implementation.
 *
 */
public class CloudClientConnector implements ICloudClient, IConnectionListener { // AÑADIDO: implements IConnectionListener
	// static
	private static final Logger _Logger = Logger.getLogger(CloudClientConnector.class.getName());

	// private var's
	private String topicPrefix = "";
	private MqttClientConnector mqttClient = null; // Este será el cliente MQTT configurado para la nube
	private IDataMessageListener dataMsgListener = null; // Listener para pasar datos al DeviceDataManager

	private int qosLevel = 1; // QoS por defecto para publicaciones/suscripciones a la nube

	// constructors
	public CloudClientConnector() {
		ConfigUtil configUtil = ConfigUtil.getInstance();

		this.topicPrefix = configUtil.getProperty(
				ConfigConst.CLOUD_GATEWAY_SERVICE,
				ConfigConst.BASE_TOPIC_KEY);

		if (this.topicPrefix == null) {
			this.topicPrefix = "/"; // Valor por defecto si no está en config
		} else {
			if (!this.topicPrefix.endsWith("/")) {
				this.topicPrefix += "/";
			}
		}
		_Logger.info("CloudClientConnector inicializado con topicPrefix: " + this.topicPrefix);
	}

	// --- ICloudClient Methods ---

	@Override
	public boolean connectClient() {
		if (this.mqttClient == null) {
			_Logger.info("Creando nueva instancia de MqttClientConnector para Cloud.GatewayService.");
			// true o ConfigConst.CLOUD_GATEWAY_SERVICE para usar la sección de config de la nube
			this.mqttClient = new MqttClientConnector(ConfigConst.CLOUD_GATEWAY_SERVICE); 
			this.mqttClient.setConnectionListener(this); // CloudClientConnector escuchará eventos de conexión de su MqttClient
		}
		
		_Logger.info("Intentando conectar CloudClientConnector (vía MqttClientConnector) al broker de la nube...");
		return this.mqttClient.connectClient();
	}

	@Override
	public boolean disconnectClient() {
		if (this.mqttClient != null && this.mqttClient.isConnected()) {
			_Logger.info("Desconectando CloudClientConnector del broker de la nube...");
			return this.mqttClient.disconnectClient();
		}
		_Logger.warning("CloudClientConnector: el cliente MQTT no existe o no está conectado. No se puede desconectar.");
		return false;
	}

	@Override
	public boolean setDataMessageListener(IDataMessageListener listener) {
		if (listener != null) {
			_Logger.info("Estableciendo DataMessageListener para CloudClientConnector.");
			this.dataMsgListener = listener;
			// También es importante pasar este listener al MqttClientConnector si los callbacks MQTT
			// necesitan invocarlo directamente (ej. si se usa la Opción 1 de suscripción genérica en MqttClientConnector).
			// Sin embargo, con la Opción 2 (listeners específicos por tópico como LedEnablementMessageListener),
			// el dataMsgListener se pasa al constructor de esos listeners específicos.
			// Si MqttClientConnector necesita el listener para otros tópicos no relacionados con la nube, se setea por separado.
			return true;
		}
		_Logger.warning("El DataMessageListener proporcionado es nulo. No se estableció.");
		return false;
	}

	@Override
	public boolean sendEdgeDataToCloud(ResourceNameEnum resource, SensorData data) {
		if (resource != null && data != null && this.mqttClient != null && this.mqttClient.isConnected()) {
			// Para Ubidots, el nombre del tópico es usualmente /v1.6/devices/{DEVICE_LABEL}/{VARIABLE_LABEL}
			// DEVICE_LABEL podría ser el nombre del GDA o del CDA (depende de cómo lo organices en Ubidots)
			// VARIABLE_LABEL sería data.getName() (ej. "Temperature", "Humidity")
			String deviceLabel = resource.getDeviceName(); // O un nombre de dispositivo GDA fijo
			String variableLabel = data.getName();
			
			String topicName = this.topicPrefix + deviceLabel + "/" + variableLabel; // Construcción básica para Ubidots
			String payload = DataUtil.getInstance().sensorDataToJson(data);

			_Logger.fine("Enviando SensorData a la nube. Tópico: " + topicName + ", Payload: " + payload.substring(0, Math.min(payload.length(), 100)) + "...");


			// Usar el método protegido de MqttClientConnector para publicar con String topicName
			// Asumiendo que publishMessage(String, byte[], int) es protected en MqttClientConnector
			return this.mqttClient.publishMessage(topicName, payload.getBytes(), this.qosLevel);
		}
		_Logger.warning("No se pudo enviar SensorData a la nube: recurso, datos nulos o cliente no conectado.");
		return false;
	}

	@Override
	public boolean sendEdgeDataToCloud(ResourceNameEnum resource, SystemPerformanceData data) {
		if (resource != null && data != null && this.mqttClient != null && this.mqttClient.isConnected()) {
			String deviceLabel = resource.getDeviceName(); // O un nombre de dispositivo GDA fijo

			// Enviar CPU Utilization
			String cpuTopic = this.topicPrefix + deviceLabel + "/" + ConfigConst.CPU_UTIL_NAME;
			SensorData cpuSensorData = new SensorData();
			cpuSensorData.setName(ConfigConst.CPU_UTIL_NAME); // El nombre de la variable en Ubidots
			cpuSensorData.setValue(data.getCpuUtil());
			String cpuPayload = DataUtil.getInstance().sensorDataToJson(cpuSensorData);
			_Logger.fine("Enviando CPU util a la nube. Tópico: " + cpuTopic + ", Payload: " + cpuPayload.substring(0, Math.min(cpuPayload.length(), 100)) + "...");
			boolean cpuSuccess = this.mqttClient.publishMessage(cpuTopic, cpuPayload.getBytes(), this.qosLevel);
			if (!cpuSuccess) {
				_Logger.warning("Fallo al enviar datos de utilización de CPU al servicio en la nube.");
			}

			// Enviar Memory Utilization
			String memTopic = this.topicPrefix + deviceLabel + "/" + ConfigConst.MEM_UTIL_NAME;
			SensorData memSensorData = new SensorData();
			memSensorData.setName(ConfigConst.MEM_UTIL_NAME); // El nombre de la variable en Ubidots
			memSensorData.setValue(data.getMemUtil());
			String memPayload = DataUtil.getInstance().sensorDataToJson(memSensorData);
			_Logger.fine("Enviando Mem util a la nube. Tópico: " + memTopic + ", Payload: " + memPayload.substring(0, Math.min(memPayload.length(), 100)) + "...");
			boolean memSuccess = this.mqttClient.publishMessage(memTopic, memPayload.getBytes(), this.qosLevel);
			if (!memSuccess) {
				_Logger.warning("Fallo al enviar datos de utilización de memoria al servicio en la nube.");
			}
			
			return cpuSuccess && memSuccess; // Retorna true solo si ambos tuvieron éxito
		}
		_Logger.warning("No se pudo enviar SystemPerformanceData a la nube: recurso, datos nulos o cliente no conectado.");
		return false;
	}

	@Override
	public boolean subscribeToCloudEvents(ResourceNameEnum resource) {
		if (this.mqttClient != null && this.mqttClient.isConnected()) {
			// Este es un método genérico. La suscripción real con un listener específico
			// se maneja mejor en onConnect() o a través de un método más específico.
			// Para PIOT-GDA-11-004, la suscripción al comando LED se hace en onConnect().
			String topicName = createTopicNameForSubscription(resource); // Método helper para crear tópico de suscripción
			 _Logger.info("Suscripción genérica a eventos de la nube para el recurso: " + resource + " en el tópico: " + topicName);
			// Para una suscripción genérica sin listener específico aquí, se usaría el callback por defecto de MqttClientConnector
			return this.mqttClient.subscribeToTopic(topicName, this.qosLevel, null); 
		}
		_Logger.warning("No se puede suscribir a eventos de la nube: cliente MQTT nulo o no conectado.");
		return false;
	}

	@Override
	public boolean unsubscribeFromCloudEvents(ResourceNameEnum resource) {
		if (this.mqttClient != null && this.mqttClient.isConnected()) {
			String topicName = createTopicNameForSubscription(resource);
			_Logger.info("Cancelando suscripción a eventos de la nube para el recurso: " + resource + " en el tópico: " + topicName);
			return this.mqttClient.unsubscribeFromTopic(topicName);
		}
		_Logger.warning("No se puede cancelar suscripción a eventos de la nube: cliente MQTT nulo o no conectado.");
		return false;
	}

	// --- IConnectionListener Methods ---
	// Estos son llamados por el MqttClientConnector interno cuando se conecta/desconecta del broker de la NUBE

	@Override
	public void onConnect() {
		_Logger.info("CloudClientConnector: Conexión MQTT con el broker de la nube COMPLETA (callback de IConnectionListener).");
		_Logger.info("Manejando suscripciones a tópicos de comandos de la nube (ej. LED)...");

		if (this.dataMsgListener == null) {
			_Logger.warning("dataMsgListener es nulo en CloudClientConnector.onConnect(). No se puede pasar al LedEnablementMessageListener.");
			// Podrías decidir no suscribirte si no hay listener, o manejarlo de otra forma.
			return;
		}

		LedEnablementMessageListener ledListener = new LedEnablementMessageListener(this.dataMsgListener);
		
		// El tópico al que te suscribes DEBE ser el tópico donde la nube publica los comandos para el LED.
        // Este tópico se define en tu plataforma en la nube (Ubidots).
        // Ejemplo: /v1.6/devices/{GDA_DEVICE_LABEL}/{LED_VARIABLE_LABEL}/lv  ("last value" o un sufijo de comando)
        // O un tópico específico que configures en Ubidots para recibir comandos.
		// El guion usa "createTopicName(ledListener.getResource().getDeviceName(), ad.getName())"
		// lo cual es confuso. ledListener.getResource() devuelve CDA_ACTUATOR_CMD_RESOURCE.
		// Necesitamos un tópico claramente definido para los comandos LED de la nube.
		// Asumamos que el GDA tiene un device label en Ubidots, y hay una variable para comandos LED.

		String gdaDeviceLabel = ConfigUtil.getInstance().getProperty(ConfigConst.GATEWAY_DEVICE, ConfigConst.DEVICE_LOCATION_ID_KEY, "gateway-device"); // Obtener el device label del GDA
		String ledCommandVariableLabel = ConfigConst.LED_ACTUATOR_NAME + "_command"; // o como lo llames en Ubidots

		String cloudLedCommandTopic = this.topicPrefix + gdaDeviceLabel + "/" + ledCommandVariableLabel; 
        // Para Ubidots, si quieres suscribirte a un valor de una variable, a menudo es el mismo tópico donde publicas
        // pero para comandos, Ubidots podría usar un sufijo como "/set" o un tópico de "control".
        // Revisa la documentación de Ubidots para la suscripción a comandos MQTT.
        // Si Ubidots usa el mismo tópico de variable para obtener el último valor (que podría ser un comando):
        // String cloudLedCommandTopic = this.topicPrefix + gdaDeviceLabel + "/" + ConfigConst.LED_ACTUATOR_NAME;

		_Logger.info("Suscribiéndose al tópico de comandos LED de la nube: " + cloudLedCommandTopic);
		
		// La publicación de un mensaje "para crear el tópico" es una peculiaridad de Ubidots
        // y puede no ser necesaria si el tópico se crea al definir la variable en la UI de Ubidots.
        // Si es necesario, publica un valor inicial/dummy a ese tópico:
        /*
        ActuatorData ad = new ActuatorData();
        ad.setName(ConfigConst.LED_ACTUATOR_NAME); // Nombre de la variable en Ubidots
        ad.setValue((float) 0); // Valor inicial
        String adJson = DataUtil.getInstance().actuatorDataToJson(ad);
        _Logger.info("Publicando estado inicial/dummy del LED a la nube para asegurar existencia del tópico: " + cloudLedCommandTopic);
        this.mqttClient.publishMessage(cloudLedCommandTopic, adJson.getBytes(), this.qosLevel);
        */

		// Ahora suscríbete al tópico donde esperas recibir los comandos LED de la nube
		boolean subscribed = this.mqttClient.subscribeToTopic(cloudLedCommandTopic, this.qosLevel, ledListener);
		if(subscribed) {
			_Logger.info("Suscripción al tópico de comandos LED de la nube (" + cloudLedCommandTopic + ") exitosa.");
		} else {
			_Logger.warning("Fallo al suscribirse al tópico de comandos LED de la nube (" + cloudLedCommandTopic + ").");
		}
	}

	@Override
	public void onDisconnect() {
		_Logger.info("CloudClientConnector: Conexión MQTT con el broker de la nube PERDIDA (callback de IConnectionListener).");
		// Aquí podrías implementar lógica de reintento o notificación si es necesario.
	}
	
	// --- Métodos privados de utilidad ---

	// Para crear tópicos para PUBLICAR datos A la nube (ej. estado de sensores)
	private String createTopicNameForPublish(ResourceNameEnum resource, String itemName) {
		// Para Ubidots: /v1.6/devices/{DEVICE_LABEL}/{VARIABLE_LABEL}
		// DEVICE_LABEL podría ser el deviceName de ResourceNameEnum (CDA o GDA)
		// itemName sería el nombre de la variable (ej. "temperature", "cpu_util")
		String deviceLabel = resource.getDeviceName(); 
		if (deviceLabel == null || deviceLabel.trim().isEmpty()) {
			// Usar un deviceLabel por defecto o el del GDA si el recurso no tiene uno claro
			deviceLabel = ConfigUtil.getInstance().getProperty(ConfigConst.GATEWAY_DEVICE, ConfigConst.DEVICE_LOCATION_ID_KEY, "default-device");
		}
		return (this.topicPrefix + deviceLabel + "/" + itemName).toLowerCase();
	}

	// Para crear tópicos para SUSCRIBIRSE A comandos DESDE la nube
	// Esto necesita más cuidado y depende de cómo la nube envíe los comandos.
	private String createTopicNameForSubscription(ResourceNameEnum resource) {
		// Esto es muy genérico. Para Ubidots, necesitarías saber el device_label y variable_label
		// al que te quieres suscribir para recibir comandos.
		// Por ejemplo, si la nube envía comandos al GDA para un actuador específico del CDA:
		// /v1.6/devices/{GDA_DEVICE_LABEL}/{CDA_ACTUATOR_NAME_COMMAND_VARIABLE}/lv (para obtener el último valor)
		// O un tópico de control específico definido en Ubidots.
		String deviceLabel = resource.getDeviceName(); // Podría ser el GDA o el CDA
		String resourceType = resource.getResourceType(); // Podría ser el nombre de la variable o parte de él
		
		// Ejemplo muy genérico, necesitas adaptarlo
		return (this.topicPrefix + deviceLabel + "/" + resourceType + "/cmd").toLowerCase(); 
	}


	// --- Clases Internas (Listeners Específicos por Tópico) ---

	private class LedEnablementMessageListener implements IMqttMessageListener {
		private IDataMessageListener dataMsgListener = null; // Este será DeviceDataManager
		// El recurso y tipo de actuador para los que este listener creará comandos
		private ResourceNameEnum targetCdaResource = ResourceNameEnum.CDA_ACTUATOR_CMD_RESOURCE; // El comando se enviará a este recurso del CDA
		private int actuatorTypeID = ConfigConst.LED_ACTUATOR_TYPE;
		private String actuatorItemName = ConfigConst.LED_ACTUATOR_NAME;

		LedEnablementMessageListener(IDataMessageListener dataMsgListener) {
			this.dataMsgListener = dataMsgListener;
		}

		// Este método no se usa externamente en este ejemplo, pero es buena práctica tenerlo
		public ResourceNameEnum getTargetCdaResource() {
			return this.targetCdaResource;
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) throws Exception {
			String jsonData = new String(message.getPayload(), "UTF-8"); // Especificar charset
			_Logger.info("LedEnablementMessageListener: Mensaje recibido en el tópico '" + topic + "'. Payload: " + jsonData.substring(0, Math.min(jsonData.length(),100)) + "...");
			
			try {
				// Ubidots envía un valor numérico simple para la variable, no un ActuatorData JSON completo.
				// Necesitamos interpretar este valor.
				double cloudValue;
				try {
					// Intenta parsear como JSON si Ubidots envía {"value": X}
					DataUtil util = DataUtil.getInstance();
					ActuatorData tempAd = util.jsonToActuatorData(jsonData); // Esto fallará si es solo un número
					cloudValue = tempAd.getValue();

				} catch (Exception e) {
					// Si no es JSON, intenta parsear como un número simple directamente
					_Logger.fine("Payload no es un ActuatorData JSON completo, intentando parsear como número simple.");
					cloudValue = Double.parseDouble(jsonData);
				}

				ActuatorData actuatorDataForCda = new ActuatorData();
				actuatorDataForCda.setLocationID(ConfigConst.CONSTRAINED_DEVICE); // El CDA al que va dirigido
				actuatorDataForCda.setTypeID(this.actuatorTypeID);
				actuatorDataForCda.setName(this.actuatorItemName);
				actuatorDataForCda.setValue((float)cloudValue); // Conservar el valor original de la nube

				// Convertir el valor de la nube (ej. 0 o 1) a un comando ON/OFF
				// y establecer datos de estado descriptivos
				if (cloudValue == ConfigConst.ON_COMMAND || cloudValue == 1.0) { // Asumir 1.0 es ON desde Ubidots
					_Logger.info("Comando LED de la nube interpretado como [ENCENDIDO].");
					actuatorDataForCda.setCommand(ConfigConst.ON_COMMAND);
					actuatorDataForCda.setStateData("LED ON (comando de la nube)");
				} else if (cloudValue == ConfigConst.OFF_COMMAND || cloudValue == 0.0) { // Asumir 0.0 es OFF desde Ubidots
					_Logger.info("Comando LED de la nube interpretado como [APAGADO].");
					actuatorDataForCda.setCommand(ConfigConst.OFF_COMMAND);
					actuatorDataForCda.setStateData("LED OFF (comando de la nube)");
				} else {
					_Logger.warning("Valor de comando LED de la nube no reconocido: " + cloudValue + ". Ignorando.");
					return;
				}

				if (this.dataMsgListener != null) {
					// Reenviar el comando procesado al DeviceDataManager (que es el dataMsgListener)
					// para que lo envíe al CDA.
					String jsonDataToSendToCda = DataUtil.getInstance().actuatorDataToJson(actuatorDataForCda);
					this.dataMsgListener.handleIncomingMessage(this.targetCdaResource, jsonDataToSendToCda);
				} else {
					_Logger.warning("dataMsgListener es nulo en LedEnablementMessageListener. No se puede reenviar el comando.");
				}
			} catch (NumberFormatException e) {
                _Logger.log(Level.WARNING, "Fallo al parsear el payload del mensaje del actuador LED como número: " + jsonData, e);
            } catch (Exception e) {
				_Logger.log(Level.WARNING, "Fallo general al procesar el payload del mensaje del actuador LED: " + jsonData, e);
			}
		}
	}
}