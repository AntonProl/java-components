package programmingtheiot.gda.connection.handlers;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import programmingtheiot.common.IDataMessageListener;
import programmingtheiot.common.ResourceNameEnum;
import programmingtheiot.data.SystemPerformanceData;
import programmingtheiot.data.SensorData;
import programmingtheiot.data.DataUtil; 
import java.util.logging.Logger;

/**
 * Resource handler for updating system performance data.
 */
public class UpdateSystemPerformanceResourceHandler extends CoapResource {

    private static final Logger _Logger = Logger.getLogger(UpdateSystemPerformanceResourceHandler.class.getName());
    private IDataMessageListener dataMsgListener = null;

    public UpdateSystemPerformanceResourceHandler(String resourceName) {
        super(resourceName);
    }

    public void setDataMessageListener(IDataMessageListener listener) {
        if (listener != null) {
            this.dataMsgListener = listener;
        }
    }

    @Override
    public void handlePUT(CoapExchange context) {
        ResponseCode code = ResponseCode.NOT_ACCEPTABLE;
        context.accept();

        if (this.dataMsgListener != null) {
            try {
                String jsonData = new String(context.getRequestPayload());
                SystemPerformanceData sysPerfData = DataUtil.getInstance().jsonToSystemPerformanceData(jsonData);
                this.dataMsgListener.handleSystemPerformanceMessage(ResourceNameEnum.CDA_SYSTEM_PERF_MSG_RESOURCE, sysPerfData);
                code = ResponseCode.CHANGED;
            } catch (Exception e) {
                _Logger.warning("Error handling PUT request: " + e.getMessage());
                code = ResponseCode.BAD_REQUEST;
            }
        } else {
            _Logger.info("No listener available for handling PUT request.");
            code = ResponseCode.CONTINUE;
        }

        context.respond(code, "System performance data update handled: " + super.getName());
    }

    @Override
    public void handleGET(CoapExchange context) {
        _Logger.info("GET request received for: " + super.getName());
        context.respond(ResponseCode.CONTENT, "GET not implemented for: " + super.getName());
    }

    @Override
    public void handlePOST(CoapExchange context) {
        _Logger.info("POST request received for: " + super.getName());
        context.respond(ResponseCode.METHOD_NOT_ALLOWED, "POST not implemented for: " + super.getName());
    }

    @Override
    public void handleDELETE(CoapExchange context) {
        _Logger.info("DELETE request received for: " + super.getName());
        context.respond(ResponseCode.METHOD_NOT_ALLOWED, "DELETE not implemented for: " + super.getName());
    }
}
