/**
 * 
 * This class is part of the Programming the Internet of Things
 * project, and is available via the MIT License, which can be
 * found in the LICENSE file at the top level of this repository.
 * 
 * Copyright (c) 2020 by Andrew D. King
 */

package programmingtheiot.part03.integration.connection;

import static org.junit.Assert.*;

import java.util.Set;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.WebLink;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import programmingtheiot.common.DefaultDataMessageListener;
import programmingtheiot.common.IDataMessageListener;
import programmingtheiot.common.ResourceNameEnum;
import programmingtheiot.data.DataUtil;
import programmingtheiot.data.SystemPerformanceData;
import programmingtheiot.gda.connection.*;

/**
 * This test case class contains very basic integration tests for
 * CoapClientToServerConnector. It should not be considered complete,
 * but serve as a starting point for the student implementing
 * additional functionality within their Programming the IoT
 * environment.
 *
 */
public class CoapClientToServerConnectorTest {
	// static

	public static final int DEFAULT_TIMEOUT = 120000; // 2 minutes
	public static final boolean USE_DEFAULT_RESOURCES = true;

	private static final Logger _Logger = Logger.getLogger(CoapClientToServerConnectorTest.class.getName());

	private static CoapServerGateway _ServerGateway = null;

	// member var's

	private CoapClientConnector coapClient = null;
	private IDataMessageListener dataMsgListener = null;

	// test setup methods

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		_ServerGateway = new CoapServerGateway(new DefaultDataMessageListener());

		boolean serverStarted = _ServerGateway.startServer();
		if (!serverStarted) {
			_Logger.severe("Failed to start CoAP server. Ensure the server is configured correctly.");
		}
		assertTrue("CoAP server failed to start. Check server configuration.", serverStarted);
	}

	/**
	 * @throws java.lang.Exception
	 *                             boolean serverStopped =
	 *                             _ServerGateway.stopServer();
	 *                             if (!serverStopped) {
	 *                             _Logger.severe("Failed to stop CoAP server.
	 *                             Ensure no issues with server shutdown.");
	 *                             }
	 *                             assertTrue("CoAP server failed to stop. Check
	 *                             server shutdown process.", serverStopped);
	 * @AfterClass
	 *             public static void tearDownAfterClass() throws Exception
	 *             {
	 *             assertTrue(_ServerGateway.stopServer());
	 *             }
	 * 
	 *             /**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		this.coapClient = new CoapClientConnector();
		this.dataMsgListener = new DefaultDataMessageListener();

		this.coapClient.setDataMessageListener(this.dataMsgListener);
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}

	// test methods

	/**
	 * 
	 */
	@Test
	public void testConnectAndDiscover() {
		_Logger.info("Starting testConnectAndDiscover...");
		boolean result = this.coapClient.sendDiscoveryRequest(DEFAULT_TIMEOUT);
		_Logger.info("Result of sendDiscoveryRequest: " + result);
		assertTrue("Discovery request should succeed. Verify the server is running and reachable.", result);
		_Logger.info("testConnectAndDiscover completed.");
	}


	@Test
	public void testRunSimpleCoapServerGatewayIntegration() {
		try {
			String url = "coap://localhost:5683";

			CoapServerGateway csg = new CoapServerGateway(); // assumes the no-arg constructor will create all resources internally
			csg.startServer();

			CoapClient clientConn = new CoapClient(url);

			Set<WebLink> wlSet = clientConn.discover();

			if (wlSet != null) {
				for (WebLink wl : wlSet) {
					_Logger.info(" --> WebLink: " + wl.getURI() + ". Attributes: " + wl.getAttributes());
				}
			}

			Thread.sleep(DEFAULT_TIMEOUT); // DEFAULT_TIMEOUT is in milliseconds - for instance, 120000 (2 minutes)

			csg.stopServer();
		} catch (Exception e) {
			_Logger.severe("An error occurred: " + e.getMessage());
		}
	}
	// log a message!
}
