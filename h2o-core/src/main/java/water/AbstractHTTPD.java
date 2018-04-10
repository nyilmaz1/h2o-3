package water;


import org.eclipse.jetty.jaas.JAASLoginService;
import org.eclipse.jetty.security.*;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import water.util.Log;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

public abstract class AbstractHTTPD {

  private final H2O.BaseArgs _args;

  protected String _ip;
  protected int _port;

  // Jetty server object.
  protected Server _server;

  protected AbstractHTTPD(H2O.BaseArgs args) {
    _args = args;
  }

  /**
   * @return URI scheme
   */
  public String getScheme() {
    if (_args.jks != null) {
      return "https";
    }
    else {
      return "http";
    }
  }

  /**
   * @return Port number
   */
  public int getPort() {
    return _port;
  }

  /**
   * @return IP address
   */
  public String getIp() {
    return _ip;
  }

  /**
   * @return Server object
   */
  public Server getServer() {
    return _server;
  }

  public void setServer(Server value) {
    _server = value;
  }

  public void setup(String ip, int port) {
    _ip = ip;
    _port = port;
    System.setProperty("org.eclipse.jetty.server.Request.maxFormContentSize", Integer.toString(Integer.MAX_VALUE));
  }

  /**
   * Choose a Port and IP address and start the Jetty server.
   *
   * @throws Exception
   */
  public void start(String ip, int port) throws Exception {
    setup(ip, port);
    if (_args.jks != null) {
      startHttps();
    }
    else {
      startHttp();
    }
  }

  protected void createServer(Connector connector) throws Exception {
    _server.setConnectors(new Connector[]{connector});

    if (_args.hash_login || _args.ldap_login || _args.kerberos_login || _args.pam_login) {
      // REFER TO http://www.eclipse.org/jetty/documentation/9.1.4.v20140401/embedded-examples.html#embedded-secured-hello-handler
      if (_args.login_conf == null) {
        Log.err("Must specify -login_conf argument");
        H2O.exit(1);
      }

      LoginService loginService;
      if (_args.hash_login) {
        Log.info("Configuring HashLoginService");
        loginService = new HashLoginService("H2O", _args.login_conf);
      }
      else if (_args.ldap_login) {
        Log.info("Configuring JAASLoginService (with LDAP)");
        System.setProperty("java.security.auth.login.config", _args.login_conf);
        loginService = new JAASLoginService("ldaploginmodule");
      }
      else if (_args.kerberos_login) {
        Log.info("Configuring JAASLoginService (with Kerberos)");
        System.setProperty("java.security.auth.login.config",_args.login_conf);
        loginService = new JAASLoginService("krb5loginmodule");
      }
      else if (_args.pam_login) {
        Log.info("Configuring JAASLoginService (with PAM)");
        System.setProperty("java.security.auth.login.config",_args.login_conf);
        loginService = new JAASLoginService("pamloginmodule");
      }
      else {
        throw failEx("Unexpected authentication method selected");
      }
      IdentityService identityService = new DefaultIdentityService();
      loginService.setIdentityService(identityService);
      _server.addBean(loginService);

      // Set a security handler as the first handler in the chain.
      ConstraintSecurityHandler security = new ConstraintSecurityHandler();

      // Set up a constraint to authenticate all calls, and allow certain roles in.
      Constraint constraint = new Constraint();
      constraint.setName("auth");
      constraint.setAuthenticate(true);


      constraint.setRoles(new String[]{Constraint.ANY_AUTH});

      ConstraintMapping mapping = new ConstraintMapping();
      mapping.setPathSpec("/*"); // Lock down all API calls
      mapping.setConstraint(constraint);
      security.setConstraintMappings(Collections.singletonList(mapping));

      // Authentication / Authorization
      Authenticator authenticator;
      if (_args.form_auth) {
        BasicAuthenticator basicAuthenticator = new BasicAuthenticator();
        FormAuthenticator formAuthenticator = new FormAuthenticator("/login", "/loginError", false);
        authenticator = new DelegatingAuthenticator(basicAuthenticator, formAuthenticator);
      } else {
        authenticator = new BasicAuthenticator();
      }
      security.setLoginService(loginService);
      security.setAuthenticator(authenticator);

      HashSessionIdManager idManager = new HashSessionIdManager();
      _server.setSessionIdManager(idManager);

      HashSessionManager manager = new HashSessionManager();
      if (_args.session_timeout > 0)
        manager.setMaxInactiveInterval(_args.session_timeout * 60);

      SessionHandler sessionHandler = new SessionHandler(manager);
      sessionHandler.setHandler(security);

      // Pass-through to H2O if authenticated.
      registerHandlers(security);
      _server.setHandler(sessionHandler);
    } else {
      registerHandlers(_server);
    }

    _server.start();
  }

  protected abstract RuntimeException failEx(String message);

  protected ConnectionFactory makeHttpConnectionFactory() {
    HttpConfiguration http_config = new HttpConfiguration();
    http_config.setSendServerVersion(false);
    return new HttpConnectionFactory(http_config);
  }

  protected void startHttp() throws Exception {
    _server = new Server();
    ServerConnector httpConnector = new ServerConnector(_server, makeHttpConnectionFactory());
    httpConnector.setHost(_ip);
    httpConnector.setPort(_port);
    createServer(httpConnector);
  }

  /**
   * This implementation is based on http://blog.denevell.org/jetty-9-ssl-https.html
   *
   * @throws Exception
   */
  private void startHttps() throws Exception {
    _server = new Server();

    SslContextFactory sslContextFactory = new SslContextFactory(_args.jks);
    sslContextFactory.setKeyStorePassword(_args.jks_pass);
    ServerConnector httpsConnector = new ServerConnector(_server, sslContextFactory, makeHttpConnectionFactory());
    if (getIp() != null) {
      httpsConnector.setHost(getIp());
    }
    httpsConnector.setPort(getPort());
    createServer(httpsConnector);

  }

  /**
   * Stop Jetty server after it has been started.
   * This is unlikely to ever be called by H2O until H2O supports graceful shutdown.
   *
   * @throws Exception
   */
  public void stop() throws Exception {
    if (_server != null) {
      _server.stop();
    }
  }

  /**
   * Hook up Jetty handlers.  Do this before start() is called.
   */
  public void registerHandlers(HandlerWrapper handlerWrapper) {
    // Both security and session handlers are already created (Note: we don't want to create a new separate session
    // handler just for ServletContextHandler - we want to have just one SessionHandler & SessionManager)
    ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS
    );

    if(null != _args.context_path && ! _args.context_path.isEmpty()) {
      context.setContextPath(_args.context_path);
    } else {
      context.setContextPath("/");
    }

    registerHandlers(handlerWrapper, context);
  }

  protected abstract void registerHandlers(HandlerWrapper handlerWrapper, ServletContextHandler context);

  protected void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message);
  }

  public class AuthenticationHandler extends AbstractHandler {
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException {

      if (!_args.ldap_login && !_args.kerberos_login && !_args.pam_login) return;

      String loginName = request.getUserPrincipal().getName();
      if (!loginName.equals(_args.user_name)) {
        Log.warn("Login name (" + loginName + ") does not match cluster owner name (" + _args.user_name + ")");
        sendUnauthorizedResponse(response, "Login name does not match cluster owner name");
        baseRequest.setHandled(true);
      }
    }
  }

}
