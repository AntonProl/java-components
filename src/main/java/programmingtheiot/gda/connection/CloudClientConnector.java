/**
 * This class is part of the Programming the Internet of Things project.
 * 
 * It is provided as a simple shell to guide the student and assist with
 * implementation for the Programming the Internet of Things exercises,
 * and designed to be modified by the student as needed.
 */

package programmingtheiot.gda.connection;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import programmingtheiot.common.ConfigConst;
import programmingtheiot.common.ConfigUtil;
import programmingtheiot.common.IDataMessageListener;
import programmingtheiot.common.ResourceNameEnum;
import programmingtheiot.data.DataUtil;
import programmingtheiot.data.SensorData;
import programmingtheiot.data.SystemPerformanceData;

/**
 * Shell representation of class for student implementation.
 *
 */
public class CloudClientConnector implements ICloudClient {
	// static

	private static final Logger _Logger = Logger.getLogger(CloudClientConnector.class.getName());

	// private var's
	private String topicPrefix = "";
	private MqttClientConnector mqttClient = null;
	private IDataMessageListener dataMsgListener = null;

	// TODO: establecer a 0 o 1, dependiendo de cuál sea preferido para tu
	// implementación
	private int qosLevel = 1;

	// constructors

	/**
	 * Default.
	 * 
	 */
	public CloudClientConnector() {
		ConfigUtil configUtil = ConfigUtil.getInstance();

		this.topicPrefix = configUtil.getProperty(
				ConfigConst.CLOUD_GATEWAY_SERVICE,
				ConfigConst.BASE_TOPIC_KEY);

		// Dependiendo del servicio en la nube, los nombres de los tópicos pueden o no
		// comenzar con un "/",
		// así que este código debería actualizarse de acuerdo con las convenciones de
		// nomenclatura de tópicos del proveedor de servicios en la nube
		if (topicPrefix == null) {
			topicPrefix = "/";
		} else {
			if (!topicPrefix.endsWith("/")) {
				topicPrefix += "/";
			}
		}
	}

	// public methods

	@Override
	public boolean connectClient() {
		if (this.mqttClient == null) {
			// TODO: cualquiera de las líneas debería funcionar con actualizaciones
			// recientes a `MqttClientConnector`
			// this.mqttClient = new MqttClientConnector(true);
			this.mqttClient = new MqttClientConnector(ConfigConst.CLOUD_GATEWAY_SERVICE);
		}

		// NOTA: Si MqttClientConnector está usando el cliente asíncrono, no tendremos
		// una conexión
		// completa al broker MQTT alojado en la nube hasta que el callback
		// connectComplete()
		// de MqttClientConnector sea invocado. Los detalles pertenecientes al uso
		// de IConnectionListener se cubren en PIOT-GDA-11-001 y PIOT-GDA-11-004.
		return this.mqttClient.connectClient();
	}

	@Override
	public boolean disconnectClient() {
		if (this.mqttClient != null && this.mqttClient.isConnected()) {
			return this.mqttClient.disconnectClient();
		}

		return false;
	}

	@Override
	public boolean setDataMessageListener(IDataMessageListener listener) {
		return false;
	}

	@Override
	public boolean sendEdgeDataToCloud(ResourceNameEnum resource, SensorData data) {
		if (resource != null && data != null) {
			String payload = DataUtil.getInstance().sensorDataToJson(data);

			return publishMessageToCloud(resource, data.getName(), payload);
		}

		return false;
	}

	@Override
	public boolean sendEdgeDataToCloud(ResourceNameEnum resource, SystemPerformanceData data) {
		if (resource != null && data != null) {
			// envía la lectura como una representación de SensorData
			SensorData cpuData = new SensorData();
			cpuData.updateData(data);
			cpuData.setName(ConfigConst.CPU_UTIL_NAME);
			cpuData.setValue(data.getCpuUtil());

			boolean cpuDataSuccess = sendEdgeDataToCloud(resource, cpuData);

			if (!cpuDataSuccess) {
				_Logger.warning("Fallo al enviar datos de utilización de CPU al servicio en la nube.");
			}

			// envía la lectura como una representación de SensorData
			SensorData memData = new SensorData();
			memData.updateData(data);
			memData.setName(ConfigConst.MEM_UTIL_NAME);
			memData.setValue(data.getMemUtil());

			boolean memDataSuccess = sendEdgeDataToCloud(resource, memData);

			if (!memDataSuccess) {
				_Logger.warning("Fallo al enviar datos de utilización de memoria al servicio en la nube.");
			}

			return (cpuDataSuccess == memDataSuccess);
		}

		return false;
	}

	@Override
	public boolean subscribeToCloudEvents(ResourceNameEnum resource) {
		boolean success = false;

		String topicName = null;

		if (this.mqttClient != null && this.mqttClient.isConnected()) {
			topicName = createTopicName(resource);

			// NOTA: Esta es una llamada de suscripción genérica - si usas este enfoque,
			// necesitarás actualizar this.mqttClient.messageReceived() para
			// (1) identificar el origen del mensaje (ej., CDA o Nube),
			// (2) determinar el tipo de mensaje (ej., comando de actuador), y
			// (3) convertir el payload en un contenedor de datos (ej., ActuatorData)
			//
			// Una vez que determines el origen y tipo del mensaje, y conviertas el
			// payload a su contenedor de datos apropiado, puedes determinar
			// dónde enrutar el mensaje (ej., enviar a la instancia IDataMessageListener
			// (que será DeviceDataManager)).
			this.mqttClient.subscribeToTopic(topicName, this.qosLevel, null);

			success = true;
		} else {
			_Logger.warning(
					"Métodos de suscripción solo disponibles para MQTT. Sin conexión MQTT al broker. Ignorando. Tópico: "
							+ topicName);
		}

		return success;
	}

	@Override
	public boolean unsubscribeFromCloudEvents(ResourceNameEnum resource) {
		boolean success = false;

		String topicName = null;

		if (this.mqttClient != null && this.mqttClient.isConnected()) {
			topicName = createTopicName(resource);

			this.mqttClient.unsubscribeFromTopic(topicName);

			success = true;
		} else {
			_Logger.warning(
					"Método de cancelación de suscripción solo disponible para MQTT. Sin conexión MQTT al broker. Ignorando. Tópico: "
							+ topicName);
		}

		return success;
	}

	// private methods

	private String createTopicName(ResourceNameEnum resource) {
		return createTopicName(resource.getDeviceName(), resource.getResourceType());
	}

	private String createTopicName(String deviceName, String resourceTypeName) {
		return this.topicPrefix + deviceName + "/" + resourceTypeName;
	}

	private boolean publishMessageToCloud(ResourceNameEnum resource, String itemName, String payload) {
		String topicName = createTopicName(resource) + "-" + itemName;
		return publishMessageToCloud(topicName, payload);
	}

	private boolean publishMessageToCloud(String topicName, String payload) {
		try {
			_Logger.finest("Publicando valor(es) de payload a CSP: " + topicName);

			this.mqttClient.publishMessage(topicName, payload.getBytes(), this.qosLevel);

			// NOTA: Dependiendo del servicio en la nube, puede ser necesario 'estrangular'
			// los mensajes publicados limitándolos, por ejemplo, a no más de uno
			// por segundo. Aunque hay una variedad de formas de lograr esto,
			// a continuación se describen brevemente dos técnicas que puede valer la pena
			// considerar
			// si esta es una limitación que necesitas manejar en tu código:
			//
			// 1) Añadir un retraso artificial después de la llamada a
			// this.mqttClient.publishMessage().
			// Esto se puede implementar durmiendo hasta un segundo después de la llamada.
			// Sin embargo, también puede afectar adversamente el flujo del programa, ya que
			// este sleep
			// bloqueará DeviceDataManager, que invocó uno de los métodos
			// sendEdgeDataToCloud()
			// que condujo a esta llamada, y puede impactar negativamente tu aplicación.
			//
			// 2) Implementar una Cola que pueda almacenar tanto el payload como el tópico
			// destino, y
			// añadir un planificador para extraer el mensaje más antiguo de la Cola (cuando
			// no esté vacía)
			// a un intervalo regular (por ejemplo, una vez por segundo), y luego invocar el
			// método this.mqttClient.publishMessage().
			//
			// Ambos enfoques requieren consideraciones de diseño reflexivas, por supuesto,
			// y tus
			// requisitos pueden exigir un enfoque alternativo (o ninguno en absoluto si el
			// estrangulamiento
			// no es una preocupación). Los detalles de diseño e implementación quedan a tu
			// cargo.

			return true;
		} catch (Exception e) {
			_Logger.warning("Fallo al publicar mensaje a CSP: " + topicName);
		}

		return false;
	}
}
