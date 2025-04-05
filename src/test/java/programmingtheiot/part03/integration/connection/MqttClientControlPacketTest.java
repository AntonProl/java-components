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

import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import programmingtheiot.common.ConfigConst;
import programmingtheiot.common.ConfigUtil;
import programmingtheiot.common.IDataMessageListener;
import programmingtheiot.common.ResourceNameEnum;
import programmingtheiot.data.*;
import programmingtheiot.gda.connection.*;

/**
 * This test case class contains very basic integration tests for
 * MqttClientControlPacketTest. It should not be considered complete,
 * but serve as a starting point for the student implementing
 * additional functionality within their Programming the IoT
 * environment.
 *
 */
public class MqttClientControlPacketTest
{
	// static
	
	private static final Logger _Logger =
		Logger.getLogger(MqttClientControlPacketTest.class.getName());
	
	
	// member var's
	
	private MqttClientConnector mqttClient = null;
	
	
	// test setup methods
	
	@Before
	public void setUp() throws Exception
	{
		this.mqttClient = new MqttClientConnector();
	}
	
	@After
	public void tearDown() throws Exception
	{
		if (this.mqttClient != null) {
			this.mqttClient.disconnectClient();
		}
	}
	
	// test methods
	
	@Test
	public void testConnectAndDisconnect()
	{
		assertTrue(this.mqttClient.connectClient());
		assertTrue(this.mqttClient.disconnectClient());
	}
	
	@Test
	public void testServerPing()
	{
		assertTrue(this.mqttClient.connectClient());
		assertTrue(this.mqttClient.pingServer());
		assertTrue(this.mqttClient.disconnectClient());
	}
	
	@Test
	public void testPubSub()
	{
		assertTrue(this.mqttClient.connectClient());
		
		String topic = ResourceNameEnum.CDA_MGMT_STATUS_MSG_RESOURCE.getResourceName();
		String message = "Test message";
		
		this.mqttClient.subscribeToTopic(ResourceNameEnum.CDA_MGMT_STATUS_MSG_RESOURCE, 1);
		assertTrue(this.mqttClient.publishMessage(ResourceNameEnum.CDA_MGMT_STATUS_MSG_RESOURCE, message, 1));
		
		// Simulate a delay to ensure message delivery
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			_Logger.warning("Sleep interrupted: " + e.getMessage());
		}
		
		this.mqttClient.unsubscribeFromTopic(ResourceNameEnum.CDA_MGMT_STATUS_MSG_RESOURCE);
		assertTrue(this.mqttClient.disconnectClient());
	}
}
