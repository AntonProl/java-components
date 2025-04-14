/**
 * This class is part of the Programming the Internet of Things project.
 * 
 * It is provided as a simple shell to guide the student and assist with
 * implementation for the Programming the Internet of Things exercises,
 * and designed to be modified by the student as needed.
 */ 

 package programmingtheiot.gda.connection;

 import java.util.List;
 import java.util.Queue;
 import java.util.concurrent.ArrayBlockingQueue;
 import java.util.logging.Level;
 import java.util.logging.Logger;
 
 import org.eclipse.californium.core.CoapResource;
 import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.network.Endpoint;
 import org.eclipse.californium.core.network.interceptors.MessageTracer;
 import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.elements.config.UdpConfig;

import programmingtheiot.common.ConfigConst;
 import programmingtheiot.common.IDataMessageListener;
 import programmingtheiot.common.ResourceNameEnum;
 import programmingtheiot.gda.connection.handlers.UpdateSystemPerformanceResourceHandler;

 

/**
 * Shell representation of class for student implementation.
 * 
 */
public class CoapServerGateway
{
	// static
	
	private static final Logger _Logger =
		Logger.getLogger(CoapServerGateway.class.getName());
	
	static {
		CoapConfig.register();
		UdpConfig.register();
	}

	// params
	
	private CoapServer coapServer = null;
	
	private IDataMessageListener dataMsgListener = null;
	
	
	// constructors
	
	/**
	 * Constructor.
	 * 
	 * @param dataMsgListener
	 */
	public CoapServerGateway(IDataMessageListener dataMsgListener)
	{
		super();
		
		/*
		 * Basic constructor implementation provided. Change as needed.
		 */
		
		this.dataMsgListener = dataMsgListener;
		
		initServer();
	}

		
	// public methods
	public CoapServerGateway() {
		initServer();
		CoapResource top = new CoapResource("PIOT");
		top.add(new CoapResource("ConstrainedDevice").add(new UpdateSystemPerformanceResourceHandler("SystemPerfMsg")));
		coapServer.add(top);
    }

    private void initServer() {
        coapServer = new CoapServer();
    }

	public void addResource(ResourceNameEnum resource)
	{
		if (this.coapServer != null)
		{
			Resource res = createResourceChain(resource);
			if (res != null)
			{
				this.coapServer.add(res);
			}
			else
			{
				_Logger.log(Level.WARNING, "Unable to add resource: " + resource);
			}
		}
		else
		{
			_Logger.log(Level.WARNING, "CoAP server not started.");
		}
	}
	
	public boolean hasResource(String name)
	{
		return false;
	}
	
	public void setDataMessageListener(IDataMessageListener listener)
	{
		if (listener != null) {
			this.dataMsgListener = listener;
		}
	}
	
	public boolean startServer()
	{
		try {
			if (this.coapServer != null) {
				this.coapServer.start();
	
				for (Endpoint ep : this.coapServer.getEndpoints()) {
					ep.addInterceptor(new MessageTracer());
				}
	
				return true;
			} else {
				_Logger.warning("CoAP server START fallido. Aún no inicializado.");
			}
		} catch (Exception e) {
			_Logger.log(Level.SEVERE, "Error al iniciar el servidor CoAP.", e);
		}
		return false;
	}
	
	public boolean stopServer()
	{
		try {
			if (this.coapServer != null) {
				this.coapServer.stop();
	
				return true;
			} else {
				_Logger.warning("CoAP server STOP fallido. Aún no inicializado.");
			}
		} catch (Exception e) {
			_Logger.log(Level.SEVERE, "Error al detener el servidor CoAP.", e);
		}
		return false;
	}
	
	
	// private methods
	private void createAndAddResourceChain(ResourceNameEnum resourceType, Resource resource) {
		// Lógica para descomponer resourceType y construir el árbol
		String[] resourceParts = resourceType.getResourceName().split("/");
		CoapResource current = (CoapResource) coapServer.getRoot();
		for (String part : resourceParts) {
			CoapResource child = (CoapResource) current.getChild(part);
			if (child == null) {
				child = new CoapResource(part);
				current.add(child);
			}
			current = child;
		}
		current.add(resource);
	}
	
	private Resource createResourceChain(ResourceNameEnum resource)
	{
		return null;
	}
	
	private void initServer(ResourceNameEnum ...resources)
	{
		if (this.coapServer == null)
		{
			this.coapServer = new CoapServer();
			
			// add resources
			for (ResourceNameEnum resource : resources)
			{
				Resource res = createResourceChain(resource);
				if (res != null)
				{
					this.coapServer.add(res);
				}
			}
			
			// add message tracer
			MessageTracer tracer = new MessageTracer();
			for (Endpoint endpoint : this.coapServer.getEndpoints()) {
				endpoint.addInterceptor(tracer);
			}
			
			// start server
			this.coapServer.start();
			
			_Logger.log(Level.INFO, "CoAP server started.");
		}
		else
		{
			_Logger.log(Level.WARNING, "CoAP server already started.");
		}
	}
}
