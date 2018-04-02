package org.ekstep.language.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.ekstep.common.Platform;
import org.ekstep.common.dto.Request;
import org.ekstep.common.dto.Response;
import org.ekstep.graph.common.enums.GraphEngineParams;
import org.ekstep.graph.common.enums.GraphHeaderParams;
import org.ekstep.graph.engine.router.ActorBootstrap;
import org.ekstep.graph.engine.router.GraphEngineManagers;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;


public class BaseLanguageTest {

	private static GraphDatabaseService graphDb;
	protected static String TEST_LANGUAGE = "en";
	protected static String TEST_COMMON_LANGUAGE = "language";
	private static String definitionFolder = "src/test/resources/definitions";
	
	static{
		ActorBootstrap.loadConfiguration();
	}
	
	@BeforeClass
	public static void init() throws Exception {
		createGraph();
		createDefinition();
	}
	
	@AfterClass
	public static void close() throws Exception {
		deleteGraph();
	}
		
	protected static void createGraph(){

		GraphDatabaseSettings.BoltConnector bolt = GraphDatabaseSettings.boltConnector( "0" );
        System.out.println("Starting neo4j in embedded mode");
       
        graphDb = new GraphDatabaseFactory()
		        .newEmbeddedDatabaseBuilder(new File(Platform.config.getString("graph.dir")))
		        .setConfig( bolt.type, "BOLT" )
		        .setConfig( bolt.enabled, "true" )
		        .setConfig( bolt.address, "localhost:7687" )
		        .newGraphDatabase();
		registerShutdownHook(graphDb);
	}

	private static void registerShutdownHook(final GraphDatabaseService graphDb) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				if(new File(Platform.config.getString("graph.dir")).exists())
					graphDb.shutdown();
			}
		});
	}
		
	protected static void deleteGraph() {

		try {
			Request request = new Request();
			request.setManagerName(GraphEngineManagers.GRAPH_MANAGER);
			request.setOperation("deleteGraph");
			request.getContext().put(GraphHeaderParams.graph_id.name(),
					TEST_LANGUAGE);
			Response resp = LanguageCommonTestHelper.getResponse(
					request);
			
			if (!resp.getParams().getStatus().equalsIgnoreCase("successful")) {
				System.out.println(resp.getParams().getErr() + resp.getParams().getErrmsg());
			}
			
			request = new Request();
			request.setManagerName(GraphEngineManagers.GRAPH_MANAGER);
			request.setOperation("deleteGraph");
			request.getContext().put(GraphHeaderParams.graph_id.name(),
					TEST_COMMON_LANGUAGE);
			resp = LanguageCommonTestHelper.getResponse(
					request);
			
			if (!resp.getParams().getStatus().equalsIgnoreCase("successful")) {
				System.out.println(resp.getParams().getErr() + resp.getParams().getErrmsg());
			}
			
			FileUtils.deleteDirectory(new File(Platform.config.getString("graph.dir")));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	protected static void createDefinition() throws IOException{
		File folder = new File(definitionFolder);
		for (File fileEntry : folder.listFiles()) {
			if (!fileEntry.isDirectory() && fileEntry.getName().endsWith(".json")) {
				String def_json =getJSONString(fileEntry);
				 createDefinition(def_json, TEST_LANGUAGE);
			}
		}
		File languageFolder = new File(definitionFolder+File.separatorChar+"language");
		for (File fileEntry : languageFolder.listFiles()) {
			if (!fileEntry.isDirectory() && fileEntry.getName().endsWith(".json")) {
				String def_json =getJSONString(fileEntry);
				 createDefinition(def_json, TEST_COMMON_LANGUAGE);
			}
		}
	}
	
	protected static String getJSONString(File initialFile) throws IOException{
		InputStream targetStream = new FileInputStream(initialFile);
		
		InputStreamReader isReader = new InputStreamReader(targetStream, "UTF8");
		String jsonContent = IOUtils.toString(isReader);
		
		return jsonContent;
	}
		
	protected static void createDefinition(String contentString, String graph_id) throws IOException{
		
		Request request = new Request();
		request.setManagerName(GraphEngineManagers.NODE_MANAGER);
		request.setOperation("importDefinitions");
		request.getContext().put(GraphHeaderParams.graph_id.name(),
				graph_id);
		request.put(GraphEngineParams.input_stream.name(), contentString);
		Response response = LanguageCommonTestHelper.getResponse(
				request);		
		Assert.assertEquals("successful", response.getParams().getStatus());
	}
}