package com.dotcms.rest;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.jgroups.Address;
import org.jgroups.Channel;
import org.jgroups.Event;
import org.jgroups.JChannel;
import org.jgroups.PhysicalAddress;
import org.jgroups.View;
import org.jgroups.stack.IpAddress;

import com.dotcms.cluster.bean.ESProperty;
import com.dotcms.cluster.bean.Server;
import com.dotcms.cluster.business.ClusterFactory;
import com.dotcms.cluster.business.ServerAPI;
import com.dotcms.content.elasticsearch.util.ESClient;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.CacheLocator;
import com.dotmarketing.business.DotGuavaCacheAdministratorImpl;
import com.dotmarketing.business.DotStateException;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.util.DotConfig;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.util.json.JSONArray;
import com.dotmarketing.util.json.JSONException;
import com.dotmarketing.util.json.JSONObject;


@Path("/cluster")
public class ClusterResource extends WebResource {

	 /**
     * Returns a Map of the Cache Cluster Status
     *
     * @param request
     * @param params
     * @return
     * @throws DotStateException
     * @throws DotDataException
     * @throws DotSecurityException
     * @throws JSONException
     */
    @GET
    @Path ("/getCacheClusterStatus/{params:.*}")
    @Produces ("application/json")
    public Response getCacheClusterStatus ( @Context HttpServletRequest request, @PathParam ("params") String params ) throws DotStateException, DotDataException, DotSecurityException, JSONException {

        InitDataObject initData = init( params, true, request, false );
        ResourceResponse responseResource = new ResourceResponse( initData.getParamsMap() );
        View view = ((DotGuavaCacheAdministratorImpl)CacheLocator.getCacheAdministrator().getImplementationObject()).getView();
        JChannel channel = ((DotGuavaCacheAdministratorImpl)CacheLocator.getCacheAdministrator().getImplementationObject()).getChannel();
        JSONObject jsonClusterStatusObject = new JSONObject();

        if(view!=null) {
        	List<Address> members = view.getMembers();
        	jsonClusterStatusObject.put( "clusterName", channel.getClusterName());
        	jsonClusterStatusObject.put( "open", channel.isOpen());
        	jsonClusterStatusObject.put( "numerOfNodes", members.size());
        	jsonClusterStatusObject.put( "address", channel.getAddressAsString());
        	jsonClusterStatusObject.put( "receivedBytes", channel.getReceivedBytes());
        	jsonClusterStatusObject.put( "receivedMessages", channel.getReceivedMessages());
        	jsonClusterStatusObject.put( "sentBytes", channel.getSentBytes());
        	jsonClusterStatusObject.put( "sentMessages", channel.getSentMessages());
        }


        return responseResource.response( jsonClusterStatusObject.toString() );

    }

    /**
     * Returns a Map of the Cache Cluster Nodes Status
     *
     * @param request
     * @param params
     * @return
     * @throws DotStateException
     * @throws DotDataException
     * @throws DotSecurityException
     * @throws JSONException
     */
    @GET
    @Path ("/getNodesStatus/{params:.*}")
    @Produces ("application/json")
    public Response getNodesInfo ( @Context HttpServletRequest request, @PathParam ("params") String params ) throws DotStateException, DotDataException, DotSecurityException, JSONException {

        InitDataObject initData = init( params, true, request, false );
        ResourceResponse responseResource = new ResourceResponse( initData.getParamsMap() );
        ServerAPI serverAPI = APILocator.getServerAPI();
        List<Server> servers = serverAPI.getAllServers();

        // JGroups Cache
        View view = ((DotGuavaCacheAdministratorImpl)CacheLocator.getCacheAdministrator().getImplementationObject()).getView();
        JChannel channel = ((DotGuavaCacheAdministratorImpl)CacheLocator.getCacheAdministrator().getImplementationObject()).getChannel();
        List<Address> members = view.getMembers();

        // ES Clustering
        AdminClient esClient = null;
        try {
        	esClient = new ESClient().getClient().admin();
        }  catch (Exception e) {
        	Logger.error(ClusterResource.class, "Error getting ES Client", e);
        }

        JSONArray jsonNodes = new JSONArray();
        String myServerId = serverAPI.readServerId();

        for (Server server : servers) {
        	JSONObject jsonNode = new JSONObject();
    		jsonNode.put( "serverId", server.getServerId());
    		jsonNode.put( "ipAddress", server.getIpAddress());

    		Boolean cacheStatus = false;
    		Boolean esStatus = false;
    		String nodeCacheWholeAddr = server.getIpAddress() + ":" + server.getCachePort();
    		String nodeESWholeAddr = server.getIpAddress() + ":" + server.getEsTransportTcpPort();

    		for ( Address member : members ) {
    			PhysicalAddress physicalAddr = (PhysicalAddress)channel.downcall(new Event(Event.GET_PHYSICAL_ADDRESS, member));
    			IpAddress ipAddr = (IpAddress)physicalAddr;

    			if(nodeCacheWholeAddr.equals(ipAddr.toString()) || (nodeCacheWholeAddr.replace("localhost", "127.0.0.1").equals(ipAddr.toString()))
    					|| (nodeCacheWholeAddr.replace("127.0.0.1", "localhost").equals(ipAddr.toString()))) {
    				cacheStatus = true;
    				break;
    			}
    		}

    		if(esClient!=null) {
	    		NodesInfoRequest nodesReq = new NodesInfoRequest();
	    		ActionFuture<NodesInfoResponse> afNodesRes = esClient.cluster().nodesInfo(nodesReq);
	    		NodesInfoResponse nodesRes = afNodesRes.actionGet();
	    		NodeInfo[] esNodes = nodesRes.getNodes();

	    		for (NodeInfo nodeInfo : esNodes) {
					DiscoveryNode node = nodeInfo.getNode();
					String address = node.getAddress().toString();

					if(address.contains(nodeESWholeAddr) || address.contains(nodeESWholeAddr.replace("localhost", "127.0.0.1"))) {
						esStatus = true;
						break;
					}
				}
    		}

    		if(UtilMethods.isSet(server.getLastHeartBeat())) {
    			Date now = new Date();
    			long difference = now.getTime() - server.getLastHeartBeat().getTime();
    			difference /= 1000;
    			jsonNode.put("contacted", difference);
    		}

    		jsonNode.put("cacheStatus", cacheStatus);
    		jsonNode.put("esStatus", esStatus);
    		jsonNode.put("myself", myServerId.equals(server.getServerId()));


    		//Added to the response list
    		jsonNodes.add( jsonNode );
		}

        return responseResource.response( jsonNodes.toString() );

    }

    /**
     * Returns a Map of the ES Cluster Status
     *
     * @param request
     * @param params
     * @return
     * @throws DotStateException
     * @throws DotDataException
     * @throws DotSecurityException
     * @throws JSONException
     */
    @GET
    @Path ("/getESClusterStatus/{params:.*}")
    @Produces ("application/json")
    public Response getESClusterStatus ( @Context HttpServletRequest request, @PathParam ("params") String params ) throws DotStateException, DotDataException, DotSecurityException, JSONException {

        InitDataObject initData = init( params, true, request, false );
        ResourceResponse responseResource = new ResourceResponse( initData.getParamsMap() );

        AdminClient client=null;

        JSONObject jsonNode = new JSONObject();

        try {
        	client = new ESClient().getClient().admin();
        } catch (Exception e) {
        	Logger.error(ClusterResource.class, "Error getting ES Client", e);
        	jsonNode.put("error", e.getMessage());
        	return responseResource.response( jsonNode.toString() );
        }

		ClusterHealthRequest clusterReq = new ClusterHealthRequest();
		ActionFuture<ClusterHealthResponse> afClusterRes = client.cluster().health(clusterReq);
		ClusterHealthResponse clusterRes = afClusterRes.actionGet();


		jsonNode.put("clusterName", clusterRes.getClusterName());
		jsonNode.put("numerOfNodes", clusterRes.getNumberOfNodes());
		jsonNode.put("activeShards", clusterRes.getActiveShards());
		jsonNode.put("activePrimaryShards", clusterRes.getActivePrimaryShards());
		jsonNode.put("unasignedPrimaryShards", clusterRes.getUnassignedShards());
		ClusterHealthStatus clusterStatus = clusterRes.getStatus();
		jsonNode.put("status", clusterStatus);

        return responseResource.response( jsonNode.toString() );

    }

    /**
     * Returns a Map of the ES Cluster Nodes Status
     *
     * @param request
     * @param params
     * @return
     * @throws DotStateException
     * @throws DotDataException
     * @throws DotSecurityException
     * @throws JSONException
     */
    @GET
    @Path ("/getESConfigProperties/{params:.*}")
    @Produces ("application/json")
    public Response getESConfigProperties ( @Context HttpServletRequest request, @PathParam ("params") String params ) throws DotStateException, DotDataException, DotSecurityException, JSONException {

        InitDataObject initData = init( params, true, request, false );
        ResourceResponse responseResource = new ResourceResponse( initData.getParamsMap() );

        JSONObject clusterProps = new JSONObject();
        Iterator<String> keys = DotConfig.getKeys();

        while ( keys.hasNext() ) {
        	String key = keys.next();
        	clusterProps.put( key, DotConfig.getStringProperty(key));
		}

        return responseResource.response( clusterProps.toString() );

    }

    /**
     * Returns a Map of the ES Cluster Nodes Status
     *
     * @param request
     * @param params
     * @return
     * @throws DotStateException
     * @throws DotDataException
     * @throws DotSecurityException
     * @throws JSONException
     */
    @POST

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Path ("/updateESConfigProperties/{params:.*}")
    @Produces ("application/json")
    public String updateESConfigProperties ( @Context HttpServletRequest request, @FormParam("accept") String accept ) throws DotStateException, DotDataException, DotSecurityException, JSONException {

//        InitDataObject initData = init( params, true, request, false );
//        ResourceResponse responseResource = new ResourceResponse( initData.getParamsMap() );


        JSONObject clusterProps = new JSONObject();
        Iterator<String> keys = DotConfig.getKeys();

        while ( keys.hasNext() ) {
        	String key = keys.next();
        	clusterProps.put( key, DotConfig.getStringProperty(key));
		}

//        return responseResource.response( clusterProps.toString() );
        return "true";

    }

    /**
     * Wires a new node to the Cache and ES Cluster
     *
     * @param request
     * @param params
     * @return
     * @throws DotStateException
     * @throws DotDataException
     * @throws DotSecurityException
     * @throws JSONException
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path ("/wirenode/{params:.*}")
    @Produces ("application/json")
    public Response wireNode ( @Context HttpServletRequest request, @PathParam ("params") String params ) throws DotStateException, DotDataException, DotSecurityException, JSONException {
        InitDataObject initData = init( params, true, request, false ); // TODO rejectWhenNoUser has to be true

        if(request.getContentType().startsWith(MediaType.APPLICATION_JSON)) {
            HashMap<String,String> map=new HashMap<String,String>();

            try {
	            JSONObject obj = new JSONObject(IOUtils.toString(request.getInputStream()));

	            Iterator<String> keys = obj.keys();
	            while(keys.hasNext()) {
	                String key=keys.next();
	                Object value=obj.get(key);
	                List<String> validProperties = ESProperty.getPropertiesList();

	                if(validProperties.contains(key)) {
	                	map.put(key, value.toString());
	                }

	                ClusterFactory.addNodeToCluster(map, "SERVER_ID");
	            }
            } catch (Exception e) {
            	initData.getParamsMap().put("error", e.getMessage());
				Logger.error(ClusterResource.class, "Error wiring a new node to the Cluster", e);
			}
        }

        ResourceResponse responseResource = new ResourceResponse( initData.getParamsMap() );
        return responseResource.response( );


    }



}