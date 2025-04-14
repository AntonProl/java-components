package programmingtheiot.gda.connection.handlers;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.core.server.resources.CoapExchange;
import programmingtheiot.common.IActuatorDataListener;
import programmingtheiot.data.ActuatorData;
import programmingtheiot.data.DataUtil;

import java.util.logging.Logger;

public class GetActuatorCommandResourceHandler extends CoapResource implements IActuatorDataListener {

    // Logger para la clase
    private static final Logger _Logger = Logger.getLogger(GetActuatorCommandResourceHandler.class.getName());

    // Datos del actuador
    private ActuatorData actuatorData = null;

    /**
     * Constructor
     *
     * @param resourceName Nombre del recurso CoAP
     */
    public GetActuatorCommandResourceHandler(String resourceName) {
        super(resourceName);

        // Configurar como recurso observable
        super.setObservable(true);
    }

    /**
     * Método de callback para actualizaciones de datos del actuador.
     *
     * @param data Datos del actuador
     * @return true si la actualización fue exitosa; false en caso contrario
     */
    @Override
    public boolean onActuatorDataUpdate(ActuatorData data) {
        if (data != null) {
            if (this.actuatorData == null) {
                this.actuatorData = new ActuatorData();
            }

            this.actuatorData.updateData(data);

            // Notificar a los clientes conectados
            super.changed();

            _Logger.fine("Datos del actuador actualizados para URI: " + super.getURI() + ": Valor de los datos = " + this.actuatorData.getValue());

            return true;
        }

        return false;
    }

    /**
     * Maneja las solicitudes GET entrantes.
     *
     * @param context Referencia al contexto de la solicitud
     */
    @Override
    public void handleGET(CoapExchange context) {
        if (context == null) {
            _Logger.warning("El contexto de CoapExchange es nulo.");
            return;
        }

        // Aceptar la solicitud
        context.accept();

        // Convertir los datos del actuador a JSON
        String jsonData = (this.actuatorData != null) ?
            DataUtil.getInstance().actuatorDataToJson(this.actuatorData) :
            "{}";

        // Registrar el manejo de la solicitud
        _Logger.info("Solicitud GET recibida para URI: " + super.getURI());

        // Enviar respuesta con el código CONTENT y los datos en formato JSON
        context.respond(ResponseCode.CONTENT, jsonData);
    }
}
