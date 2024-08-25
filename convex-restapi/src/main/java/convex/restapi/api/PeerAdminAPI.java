package convex.restapi.api;

import java.util.Arrays;
import java.util.HashSet;

import convex.peer.Server;
import convex.restapi.RESTServer;
import convex.restapi.model.CreateAccountResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;

public class PeerAdminAPI extends ABaseAPI {

	public Server server;

	public PeerAdminAPI(RESTServer restServer) {
		super(restServer);
		server = restServer.getServer();
	}

	private static final String ROUTE = "/api/v1/";

	@Override
	public void addRoutes(Javalin app) {
		String prefix = ROUTE;

		app.post(prefix + "peer/shutdown", this::shutDown);
	
	}

	@OpenApi(path = ROUTE + "peer/shutdown", 
			methods = HttpMethod.POST, 
			operationId = "shutdownPeer", 
			tags = { "Admin"},
			summary = "Shut down the current peer", 
			responses = {
				@OpenApiResponse(
						status = "200", 
						description = "Peer shutdown initiated", 
						content = {
							@OpenApiContent(
									type = "application/json", 
									from = CreateAccountResponse.class) })
				})
	public void shutDown(Context ctx) {
		ensureLocalAdmin(ctx);
		try {
			server.shutdown();
		} catch (Exception e) {
			e.printStackTrace();
			throw new InternalServerErrorResponse("Failed to initiate sutdown: "+e.getMessage());
		}
		ctx.result("Shutdown initiated.");
	}
	
	private HashSet<String> authorisedIPs = new HashSet<String>(Arrays.asList("127.0.0.1","::1","[0:0:0:0:0:0:0:1]"));

	private void ensureLocalAdmin(Context ctx) {
		String ip=ctx.ip();
		if (authorisedIPs.contains(ip)) {
			return;
		} else {
			throw new io.javalin.http.UnauthorizedResponse("Can't performa admin actions from IP: "+ip);
		}
	}



}