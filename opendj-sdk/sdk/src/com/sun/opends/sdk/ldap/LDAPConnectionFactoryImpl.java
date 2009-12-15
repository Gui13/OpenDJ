/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2009 Sun Microsystems, Inc.
 */

package com.sun.opends.sdk.ldap;



import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import javax.net.ssl.SSLContext;

import org.opends.sdk.*;
import org.opends.sdk.ldap.LDAPConnectionOptions;
import org.opends.sdk.controls.*;
import org.opends.sdk.extensions.StartTLSRequest;
import org.opends.sdk.responses.Responses;
import org.opends.sdk.responses.Result;

import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.filterchain.PatternFilterChainFactory;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.ssl.BlockingSSLHandshaker;
import com.sun.grizzly.ssl.SSLEngineConfigurator;
import com.sun.grizzly.ssl.SSLFilter;
import com.sun.grizzly.ssl.SSLHandshaker;
import com.sun.grizzly.streams.StreamWriter;
import com.sun.opends.sdk.util.Validator;



/**
 * LDAP connection factory implementation.
 */
public final class LDAPConnectionFactoryImpl extends
    AbstractConnectionFactory<AsynchronousConnection> implements
    ConnectionFactory<AsynchronousConnection>
{
  private final class LDAPTransport extends AbstractLDAPTransport
  {

    @Override
    protected LDAPMessageHandler getMessageHandler(
        com.sun.grizzly.Connection<?> connection)
    {
      return ldapConnectionAttr.get(connection).getLDAPMessageHandler();
    }



    @Override
    protected void removeMessageHandler(
        com.sun.grizzly.Connection<?> connection)
    {
      ldapConnectionAttr.remove(connection);
    }

  }



  private static class FailedImpl implements
      FutureResult<AsynchronousConnection>
  {
    private volatile ErrorResultException exception;



    private FailedImpl(ErrorResultException exception)
    {
      this.exception = exception;
    }



    public boolean cancel(boolean mayInterruptIfRunning)
    {
      return false;
    }



    public AsynchronousConnection get() throws InterruptedException,
        ErrorResultException
    {
      throw exception;
    }



    public AsynchronousConnection get(long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException,
        ErrorResultException
    {
      throw exception;
    }



    public boolean isCancelled()
    {
      return false;
    }



    public boolean isDone()
    {
      return false;
    }



    public int getRequestID()
    {
      return -1;
    }
  }



  private class ResultFutureImpl implements
      FutureResult<AsynchronousConnection>,
      com.sun.grizzly.CompletionHandler<com.sun.grizzly.Connection>,
      ResultHandler<Result>
  {
    private volatile AsynchronousConnection connection;

    private volatile ErrorResultException exception;

    private volatile Future<com.sun.grizzly.Connection> connectFuture;

    private volatile FutureResult<?> sslFuture;

    private final CountDownLatch latch = new CountDownLatch(1);

    private final ResultHandler<? super AsynchronousConnection> handler;

    private boolean cancelled;



    private ResultFutureImpl(
        ResultHandler<? super AsynchronousConnection> handler)
    {
      this.handler = handler;
    }



    public boolean cancel(boolean mayInterruptIfRunning)
    {
      cancelled = connectFuture.cancel(mayInterruptIfRunning)
          || sslFuture != null
          && sslFuture.cancel(mayInterruptIfRunning);
      if (cancelled)
      {
        latch.countDown();
      }
      return cancelled;
    }



    public AsynchronousConnection get() throws InterruptedException,
        ErrorResultException
    {
      latch.await();
      if (cancelled)
      {
        throw new CancellationException();
      }
      if (exception != null)
      {
        throw exception;
      }
      return connection;
    }



    public AsynchronousConnection get(long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException,
        ErrorResultException
    {
      latch.await(timeout, unit);
      if (cancelled)
      {
        throw new CancellationException();
      }
      if (exception != null)
      {
        throw exception;
      }
      return connection;
    }



    public boolean isCancelled()
    {
      return cancelled;
    }



    public boolean isDone()
    {
      return latch.getCount() == 0;
    }



    public int getRequestID()
    {
      return -1;
    }



    /**
     * {@inheritDoc}
     */
    public void cancelled(com.sun.grizzly.Connection connection)
    {
      // Ignore this.
    }



    /**
     * {@inheritDoc}
     */
    public void completed(com.sun.grizzly.Connection connection,
        com.sun.grizzly.Connection result)
    {
      LDAPConnection ldapConn = adaptConnection(connection);
      this.connection = adaptConnection(connection);

      if (options.getSSLContext() != null && options.useStartTLS())
      {
        StartTLSRequest startTLS = new StartTLSRequest(options
            .getSSLContext());
        sslFuture = this.connection.extendedRequest(startTLS, this);
      }
      else if (options.getSSLContext() != null)
      {
        try
        {
          ldapConn.installFilter(sslFilter);
          ldapConn.performSSLHandshake(sslHandshaker,
              sslEngineConfigurator);
          latch.countDown();
          if (handler != null)
          {
            handler.handleResult(this.connection);
          }
        }
        catch (CancellationException ce)
        {
          // Handshake cancelled.
          latch.countDown();
        }
        catch (ErrorResultException throwable)
        {
          exception = throwable;
          latch.countDown();
          if (handler != null)
          {
            handler.handleErrorResult(exception);
          }
        }
      }
      else
      {
        latch.countDown();
        if (handler != null)
        {
          handler.handleResult(this.connection);
        }
      }
    }



    /**
     * {@inheritDoc}
     */
    public void failed(com.sun.grizzly.Connection connection,
        Throwable throwable)
    {
      exception = adaptConnectionException(throwable);
      latch.countDown();
      if (handler != null)
      {
        handler.handleErrorResult(exception);
      }
    }



    /**
     * {@inheritDoc}
     */
    public void updated(com.sun.grizzly.Connection connection,
        com.sun.grizzly.Connection result)
    {
      // Ignore this.
    }



    // This is called when the StartTLS request is successful
    public void handleResult(Result result)
    {
      latch.countDown();
      if (handler != null)
      {
        handler.handleResult(connection);
      }
    }



    // This is called when the StartTLS request is not successful
    public void handleErrorResult(ErrorResultException error)
    {
      exception = error;
      latch.countDown();
      if (handler != null)
      {
        handler.handleErrorResult(exception);
      }
    }
  }



  private static final String LDAP_CONNECTION_OBJECT_ATTR = "LDAPConnAtr";

  private static TCPNIOTransport TCP_NIO_TRANSPORT = null;



  // FIXME: Need to figure out how this can be configured without
  // exposing internal implementation details to application.
  private static synchronized TCPNIOTransport getTCPNIOTransport()
  {
    if (TCP_NIO_TRANSPORT == null)
    {
      // Create a default transport using the Grizzly framework.
      TCP_NIO_TRANSPORT = TransportFactory.getInstance()
          .createTCPTransport();
      try
      {
        TCP_NIO_TRANSPORT.start();
      }
      catch (IOException e)
      {
        throw new RuntimeException(
            "Unable to create default connection factory provider", e);
      }

      Runtime.getRuntime().addShutdownHook(new Thread()
      {

        @Override
        public void run()
        {
          ShutdownTCPNIOTransport();
        }

      });
    }
    return TCP_NIO_TRANSPORT;
  }



  private synchronized static void ShutdownTCPNIOTransport()
  {
    if (TCP_NIO_TRANSPORT != null)
    {
      try
      {
        TCP_NIO_TRANSPORT.stop();
      }
      catch (Exception e)
      {
        // Ignore.
      }

      // try
      // {
      // TCP_NIO_TRANSPORT.getWorkerThreadPool().shutdown();
      // }
      // catch (Exception e)
      // {
      // // Ignore.
      // }

      TCP_NIO_TRANSPORT = null;
    }
  }



  private final Attribute<LDAPConnection> ldapConnectionAttr;

  private final InetSocketAddress socketAddress;

  private final TCPNIOTransport transport;

  private final SSLHandshaker sslHandshaker = new BlockingSSLHandshaker();

  private final SSLEngineConfigurator sslEngineConfigurator;

  private final SSLFilter sslFilter;

  private final Map<String, ControlDecoder<?>> knownControls;

  private final LDAPTransport ldapTransport = new LDAPTransport();

  private final LDAPConnectionOptions options;



  /**
   * Creates a new LDAP connection factory implementation which can be
   * used to create connections to the Directory Server at the provided
   * host and port address using provided connection options.
   *
   * @param host
   *          The host name.
   * @param port
   *          The port number.
   * @param options
   *          The LDAP connection options to use when creating
   *          connections.
   * @throws NullPointerException
   *           If {@code host} or {@code options} was {@code null}.
   */
  public LDAPConnectionFactoryImpl(String host, int port,
      LDAPConnectionOptions options) throws NullPointerException
  {
    this(host, port, options, getTCPNIOTransport());
  }



  private LDAPConnectionFactoryImpl(String host, int port,
      LDAPConnectionOptions options, TCPNIOTransport transport)
  {
    Validator.ensureNotNull(host, transport, options);

    this.transport = transport;
    this.ldapConnectionAttr = transport.getAttributeBuilder()
        .createAttribute(LDAP_CONNECTION_OBJECT_ATTR);
    this.socketAddress = new InetSocketAddress(host, port);
    this.options = LDAPConnectionOptions.copyOf(options);
    if (this.options.getSSLContext() == null)
    {
      this.sslEngineConfigurator = null;
      this.sslFilter = null;
    }
    else
    {
      this.sslEngineConfigurator = new SSLEngineConfigurator(
          this.options.getSSLContext(), true, false, false);
      this.sslFilter = new SSLFilter(sslEngineConfigurator,
          sslHandshaker);
    }
    this.knownControls = new HashMap<String, ControlDecoder<?>>();
    initControls();
  }



  /**
   * {@inheritDoc}
   */
  public FutureResult<AsynchronousConnection> getAsynchronousConnection(
      ResultHandler<? super AsynchronousConnection> handler)
  {
    ResultFutureImpl future = new ResultFutureImpl(handler);

    try
    {
      future.connectFuture = transport.connect(socketAddress, future);
      return future;
    }
    catch (IOException e)
    {
      ErrorResultException result = adaptConnectionException(e);
      return new FailedImpl(result);
    }
  }



  ExecutorService getHandlerInvokers()
  {
    // TODO: Threading strategies?
    return null;
  }



  SSLHandshaker getSslHandshaker()
  {
    return sslHandshaker;
  }



  SSLFilter getSSlFilter()
  {
    return sslFilter;
  }



  SSLContext getSSLContext()
  {
    return options.getSSLContext();
  }



  SSLEngineConfigurator getSSlEngineConfigurator()
  {
    return sslEngineConfigurator;
  }



  ASN1StreamWriter getASN1Writer(StreamWriter streamWriter)
  {
    return ldapTransport.getASN1Writer(streamWriter);
  }



  void releaseASN1Writer(ASN1StreamWriter asn1Writer)
  {
    ldapTransport.releaseASN1Writer(asn1Writer);
  }



  PatternFilterChainFactory getDefaultFilterChainFactory()
  {
    return ldapTransport.getDefaultFilterChainFactory();
  }



  private LDAPConnection adaptConnection(
      com.sun.grizzly.Connection<?> connection)
  {
    // Test shows that its much faster with non block writes but risk
    // running out of memory if the server is slow.
    connection.configureBlocking(true);
    connection.getStreamReader().setBlocking(true);
    connection.getStreamWriter().setBlocking(true);
    connection.setProcessor(ldapTransport
        .getDefaultFilterChainFactory().getFilterChainPattern());

    LDAPConnection ldapConnection = new LDAPConnection(connection,
        socketAddress, options.getSchema(), this);
    ldapConnectionAttr.set(connection, ldapConnection);
    return ldapConnection;
  }



  private ErrorResultException adaptConnectionException(Throwable t)
  {
    if (t instanceof ExecutionException)
    {
      t = t.getCause();
    }

    Result result = Responses.newResult(
        ResultCode.CLIENT_SIDE_CONNECT_ERROR).setCause(t)
        .setDiagnosticMessage(t.getMessage());
    return ErrorResultException.wrap(result);
  }



  ControlDecoder<?> getControlDecoder(String oid)
  {
    return knownControls.get(oid);
  }



  private void initControls()
  {
    knownControls.put(
        AccountUsabilityControl.OID_ACCOUNT_USABLE_CONTROL,
        AccountUsabilityControl.RESPONSE_DECODER);
    knownControls.put(
        AuthorizationIdentityControl.OID_AUTHZID_RESPONSE,
        AuthorizationIdentityControl.RESPONSE_DECODER);
    knownControls.put(
        EntryChangeNotificationControl.OID_ENTRY_CHANGE_NOTIFICATION,
        EntryChangeNotificationControl.DECODER);
    knownControls.put(PagedResultsControl.OID_PAGED_RESULTS_CONTROL,
        PagedResultsControl.DECODER);
    knownControls.put(PasswordExpiredControl.OID_NS_PASSWORD_EXPIRED,
        PasswordExpiredControl.DECODER);
    knownControls.put(PasswordExpiringControl.OID_NS_PASSWORD_EXPIRING,
        PasswordExpiringControl.DECODER);
    knownControls.put(
        PasswordPolicyControl.OID_PASSWORD_POLICY_CONTROL,
        PasswordPolicyControl.RESPONSE_DECODER);
    knownControls.put(PostReadControl.OID_LDAP_READENTRY_POSTREAD,
        PostReadControl.RESPONSE_DECODER);
    knownControls.put(PreReadControl.OID_LDAP_READENTRY_PREREAD,
        PreReadControl.RESPONSE_DECODER);
    knownControls.put(
        ServerSideSortControl.OID_SERVER_SIDE_SORT_RESPONSE_CONTROL,
        ServerSideSortControl.RESPONSE_DECODER);
    knownControls.put(VLVControl.OID_VLV_RESPONSE_CONTROL,
        VLVControl.RESPONSE_DECODER);
  }
}
