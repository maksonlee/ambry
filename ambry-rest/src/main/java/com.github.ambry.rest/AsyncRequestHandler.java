package com.github.ambry.rest;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implementation of {@link RestRequestHandler} that handles requests submitted by a
 * {@link NioServer} and routes them asynchronously to a {@link BlobStorageService}.
 * <p/>
 * Multiple instances are created by the {@link RequestHandlerController}  and each instance runs continuously to handle
 * requests submitted by the {@link NioServer} in a non-blocking way by queueing them.
 * The requests are then handled async through a {@link BlobStorageService}.
 * <p/>
 * The queue owned by a single AsyncRequestHandler might have parts from multiple requests interleaved but parts of the
 * same request will (have to) be in order. This ordering cannot be enforced by the AsyncRequestHandler but instead has
 * to be enforced by the {@link NioServer} (it is responsible for finding a way to use the
 * same AsyncRequestHandler for all parts of the same request - for an example implementation see
 * {@link NettyMessageProcessor}).
 * <p/>
 * These are the scaling units of the {@link RestServer} and can be scaled up and down independently of any other
 * component of the {@link RestServer}.
 */
class AsyncRequestHandler implements RestRequestHandler {
  private static long OFFER_TIMEOUT_SECONDS = 5;

  private final Thread dequeuedRequestHandlerThread;
  private final DequeuedRequestHandler dequeuedRequestHandler;
  private final ConcurrentHashMap<RestRequestMetadata, Boolean> requestsInFlight =
      new ConcurrentHashMap<RestRequestMetadata, Boolean>();
  private final LinkedBlockingQueue<RestRequestInfo> restRequestInfoQueue = new LinkedBlockingQueue<RestRequestInfo>();
  private final RestServerMetrics restServerMetrics;
  private final QueuingTimeTracker queuingTimeTracker = new QueuingTimeTracker();
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public AsyncRequestHandler(BlobStorageService blobStorageService, RestServerMetrics restServerMetrics) {
    this.restServerMetrics = restServerMetrics;
    dequeuedRequestHandler =
        new DequeuedRequestHandler(blobStorageService, restRequestInfoQueue, requestsInFlight, queuingTimeTracker,
            restServerMetrics);
    dequeuedRequestHandlerThread = new Thread(dequeuedRequestHandler);
    restServerMetrics.registerAsyncRequestHandler(this);
    logger.trace("Instantiated AsyncRequestHandler");
  }

  @Override
  public void start()
      throws InstantiationException {
    if (!isRunning()) {
      logger.info("Starting AsyncRequestHandler");
      dequeuedRequestHandlerThread.start();
      logger.info("AsyncRequestHandler has started");
    }
  }

  /**
   * Attempts to shutdown the AsyncRequestHandler gracefully by introducing {@link PoisonInfo} into the queue. Any
   * resources held by the {@link RestRequestInfo}s queued after the poison are released.
   * <p/>
   * If the graceful shutdown fails, then a shutdown is forced. Any outstanding {@link RestRequestInfo}s are not
   * handled and resources held by them are not released.
   */
  @Override
  public void shutdown() {
    if (isRunning()) {
      logger.info("Shutting down AsyncRequestHandler");
      long shutdownBeginTime = System.currentTimeMillis();
      try {
        queueRequest(new PoisonInfo());
        if (!(dequeuedRequestHandler.awaitShutdown(60, TimeUnit.SECONDS) || shutdownNow())) {
          logger.error("Shutdown of AsyncRequestHandler failed. This should not happen");
          restServerMetrics.asyncRequestHandlerShutdownError.inc();
        }
      } catch (InterruptedException e) {
        logger.error(
            "Await shutdown of AsyncRequestHandler was interrupted. The AsyncRequestHandler might not have shutdown",
            e);
        restServerMetrics.asyncRequestHandlerShutdownError.inc();
      } catch (RestServiceException e) {
        logger.error("Shutdown of AsyncRequestHandler threw RestServiceException and was aborted", e);
        restServerMetrics.asyncRequestHandlerShutdownError.inc();
      } finally {
        long shutdownTime = System.currentTimeMillis() - shutdownBeginTime;
        logger.info("AsyncRequestHandler shutdown took {} ms", shutdownTime);
        restServerMetrics.asyncRequestHandlerShutdownTimeInMs.update(shutdownTime);
      }
    }
  }

  /**
   * Queues the {@link RestRequestInfo} to be handled async. When this function returns, the {@link RestRequestInfo} is
   * not yet handled.
   * <p/>
   * To receive a callback on handling completion, a {@link RestRequestInfoEventListener}
   * needs to be added to the {@link RestRequestInfo}.
   * @param restRequestInfo - the {@link RestRequestInfo} that needs to be handled.
   * @throws RestServiceException - if there is an error in preparing and queueing the {@link RestRequestInfo}.
   */
  @Override
  public void handleRequest(RestRequestInfo restRequestInfo)
      throws RestServiceException {
    if (isRunning()) {
      if (restRequestInfo == null) {
        restServerMetrics.asyncRequestHandlerRestRequestInfoNullError.inc();
        throw new RestServiceException("RestRequestInfo received is null", RestServiceErrorCode.RestRequestInfoNull);
      } else if (restRequestInfo.getRestRequestMetadata() == null) {
        restServerMetrics.asyncRequestHandlerRestRequestMetadataNullError.inc();
        throw new RestServiceException("RestRequestMetadata is null in received RestRequestInfo",
            RestServiceErrorCode.RequestMetadataNull);
      } else if (restRequestInfo.getRestResponseHandler() == null) {
        restServerMetrics.asyncRequestHandlerRestResponseHandlerNullError.inc();
        throw new RestServiceException("RestResponseHandler is null in received RestRequestInfo",
            RestServiceErrorCode.ResponseHandlerNull);
      }
      queueRequest(restRequestInfo);
    } else {
      restServerMetrics.asyncRequestHandlerUnavailableError.inc();
      throw new RestServiceException("Requests cannot be handled because the AsyncRequestHandler is not available",
          RestServiceErrorCode.RequestHandlerUnavailable);
    }
  }

  @Override
  public void onRequestComplete(RestRequestMetadata restRequestMetadata) {
    dequeuedRequestHandler.onRequestComplete(restRequestMetadata);
  }

  @Override
  public boolean isRunning() {
    return dequeuedRequestHandlerThread.isAlive();
  }

  public int getQueueSize() {
    return restRequestInfoQueue.size();
  }

  public int getRequestsInFlightCount() {
    return requestsInFlight.size();
  }

  /**
   * Adds the {@link RestRequestInfo} to the queue of {@link RestRequestInfo}s waiting to be handled.
   * @param restRequestInfo - the {@link RestRequestInfo} that needs to be added to the queue.
   * @throws RestServiceException - if there is an error in preparing and queueing the {@link RestRequestInfo}.
   */
  private void queueRequest(RestRequestInfo restRequestInfo)
      throws RestServiceException {
    String uri =
        (restRequestInfo.getRestRequestMetadata() != null) ? restRequestInfo.getRestRequestMetadata().getUri() : null;
    logger.trace("Queueing RestRequestInfo of request {}", uri);
    boolean offerFailed = false;
    if (restRequestInfo.isFirstPart()) {
      restServerMetrics.asyncRequestHandlerRequestArrivalRate.mark();
      // This is the first part of a new request.
      // Since we are going to handle this async, we need to make sure the RestRequestMetadata is not recycled.
      // It is released in onRequestComplete() once we know that we are not going to use it anymore.
      // If the offer fails, the request will error out and onRequestComplete() will still be called. So
      // there is no need to release right away even on error.
      restRequestInfo.getRestRequestMetadata().retain();
      if (requestsInFlight.putIfAbsent(restRequestInfo.getRestRequestMetadata(), true) != null) {
        logger.warn("Request {} seems to be marked as in-flight before the first RestRequestInfo was seen", uri);
        restServerMetrics.asyncRequestHandlerRequestAlreadyInFlightError.inc();
      } else {
        logger.trace("Added request {} to requests in flight", uri);
      }
    }
    if (restRequestInfo.getRestRequestContent() != null) {
      // Since we are going to handle this async, we need to make sure this RestRequestContent is not recycled.
      // It is released in DequeuedRequestHandler::handleRequest() once the handling is complete.
      // If the offer fails, we release immediately.
      restRequestInfo.getRestRequestContent().retain();
    }
    try {
      queuingTimeTracker.startTracking(restRequestInfo);
      if (!restRequestInfoQueue.offer(restRequestInfo, OFFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        offerFailed = true;
        restServerMetrics.asyncRequestHandlerQueueOfferTooLongError.inc();
        throw new RestServiceException("Attempt to queue RestRequestInfo timed out",
            RestServiceErrorCode.RestRequestInfoQueueingFailure);
      } else {
        logger.trace("Queued RestRequestInfo of request {}", uri);
        restServerMetrics.asyncRequestHandlerQueueingRate.mark();
      }
    } catch (InterruptedException e) {
      offerFailed = true;
      restServerMetrics.asyncRequestHandlerQueueOfferInterruptedError.inc();
      throw new RestServiceException("Attempt to queue RestRequestInfo interrupted", e,
          RestServiceErrorCode.RestRequestInfoQueueingFailure);
    } finally {
      if (offerFailed) {
        queuingTimeTracker.stopTracking(restRequestInfo);
        if (restRequestInfo.getRestRequestContent() != null) {
          restRequestInfo.getRestRequestContent().release();
        }
      }
    }
  }

  /**
   * Forces immediate shutdown of the {@link DequeuedRequestHandler}. The {@link RestRequestInfo} that is being
   * currently handled might misbehave and all {@link RestRequestInfo}s still in the queue do not get handled.
   */
  private boolean shutdownNow()
      throws InterruptedException {
    logger
        .info("Forcing shutdown of AsyncRequestHandler with {} RestRequestInfos still in queue. They will be unhandled",
            restRequestInfoQueue.size());
    restServerMetrics.asyncRequestHandlerForcedShutdown.inc();
    dequeuedRequestHandler.shutdownNow();
    dequeuedRequestHandlerThread.interrupt();
    return dequeuedRequestHandler.awaitShutdown(5, TimeUnit.SECONDS);
  }
}

/**
 * Thread that handles the {@link RestRequestInfo}s in queue one by one. Can deal with handling failures.
 */
class DequeuedRequestHandler implements Runnable {
  private final BlobStorageService blobStorageService;
  private final LinkedBlockingQueue<RestRequestInfo> restRequestInfoQueue;
  private final ConcurrentHashMap<RestRequestMetadata, Boolean> requestsInFlight;
  private final RestServerMetrics restServerMetrics;
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final QueuingTimeTracker queuingTimeTracker;
  private final AtomicBoolean shutdownReady = new AtomicBoolean(false);
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public DequeuedRequestHandler(BlobStorageService blobStorageService,
      LinkedBlockingQueue<RestRequestInfo> restRequestInfoQueue,
      ConcurrentHashMap<RestRequestMetadata, Boolean> requestsInFlight, QueuingTimeTracker queuingTimeTracker,
      RestServerMetrics restServerMetrics) {
    this.blobStorageService = blobStorageService;
    this.restRequestInfoQueue = restRequestInfoQueue;
    this.requestsInFlight = requestsInFlight;
    this.queuingTimeTracker = queuingTimeTracker;
    this.restServerMetrics = restServerMetrics;
    logger.trace("Instantiated DequeuedRequestHandler");
  }

  /**
   * Handles any queued {@link RestRequestInfo}s continuously until poisoned or forced to shutdown.
   */
  @Override
  public void run() {
    while (!shutdownReady.get()) {
      try {
        RestRequestInfo restRequestInfo = null;
        try {
          restRequestInfo = restRequestInfoQueue.take();
          trackMetricsOnDequeue(restRequestInfo);
          if (restRequestInfo instanceof PoisonInfo) {
            logger.trace("Encountered PoisonInfo, shutting down");
            restRequestInfo.onComplete(null);
            emptyQueue();
            break;
          }
          handleRequest(restRequestInfo);
          logger.trace("RestRequestInfo of request {} was handled successfully",
              restRequestInfo.getRestRequestMetadata().getUri());
          onHandlingComplete(restRequestInfo, null);
        } catch (InterruptedException e) {
          logger.error("Swallowing InterruptedException during dequeueing of RestRequestInfo", e);
          restServerMetrics.dequeuedRequestHandlerQueueTakeInterruptedError.inc();
        } catch (RestServiceException e) {
          RestServiceErrorCode errorCode = e.getErrorCode();
          if (RestServiceErrorCode.getErrorCodeGroup(errorCode) == RestServiceErrorCode.BadRequest) {
            logger.debug("Handling of dequeued RestRequestInfo of request {} failed with error code {}",
                restRequestInfo.getRestRequestMetadata().getUri(), errorCode, e);
          } else {
            logger.error("Handling of dequeued RestRequestInfo of request {} failed with error code {}",
                restRequestInfo.getRestRequestMetadata().getUri(), errorCode, e);
          }
          restServerMetrics.dequeuedRequestHandlerRestRequestInfoHandlingError.inc();
          onHandlingComplete(restRequestInfo, e);
        } catch (Exception e) {
          logger.error("Handling of dequeued RestRequestInfo of request {} failed",
              restRequestInfo.getRestRequestMetadata().getUri(), e);
          restServerMetrics.dequeuedRequestHandlerRestRequestInfoHandlingError.inc();
          onHandlingComplete(restRequestInfo, e);
        }
      } catch (Exception e) {
        logger.error("Swallowing unexpected exception during dequeuing and handling of RestRequestInfo", e);
        restServerMetrics.dequeuedRequestHandlerUnexpectedExceptionError.inc();
      }
    }
    logger.trace("DequeuedRequestHandler stopped");
    shutdownLatch.countDown();
  }

  /**
   * Cleans up after a request is complete. Destroys any state that was being maintained.
   * @param restRequestMetadata - the {@link RestRequestMetadata} of the request that was completed.
   */
  public void onRequestComplete(RestRequestMetadata restRequestMetadata) {
    if (restRequestMetadata != null) {
      if (requestsInFlight.remove(restRequestMetadata) != null) {
        restServerMetrics.dequeuedRequestHandlerRequestCompletionRate.mark();
        // NOTE: some of this code might be relevant to emptyQueue() also.
        restRequestMetadata.release();
        logger.trace("Request completed - {}", restRequestMetadata.getUri());
      }
    }
  }

  /**
   * Process a dequeued {@link RestRequestInfo}. Discerns the type of {@link RestMethod} and calls the right function
   * of the {@link BlobStorageService}.
   * @param restRequestInfo - The currently de-queued {@link RestRequestInfo}.
   * @throws RestServiceException - - if there was error in handling the {@link RestRequestInfo}.
   */
  private void handleRequest(RestRequestInfo restRequestInfo)
      throws RestServiceException {
    try {
      RestMethod restMethod = restRequestInfo.getRestRequestMetadata().getRestMethod();
      logger.trace("RestMethod {} for RestRequestInfo of request {}", restMethod,
          restRequestInfo.getRestRequestMetadata().getUri());
      switch (restMethod) {
        case GET:
          blobStorageService.handleGet(restRequestInfo);
          break;
        case POST:
          blobStorageService.handlePost(restRequestInfo);
          break;
        case DELETE:
          blobStorageService.handleDelete(restRequestInfo);
          break;
        case HEAD:
          blobStorageService.handleHead(restRequestInfo);
          break;
        default:
          restServerMetrics.dequeuedRequestHandlerUnknownRestMethodError.inc();
          throw new RestServiceException("Unsupported REST method - " + restMethod,
              RestServiceErrorCode.UnsupportedRestMethod);
      }
    } finally {
      if (restRequestInfo.getRestRequestContent() != null) {
        restRequestInfo.getRestRequestContent().release();
      }
    }
  }

  /**
   * Marks that shutdown is required. When this function returns, shutdown need NOT be complete. Instead, shutdown
   * is guaranteed to happen as soon as the processing of the current {@link RestRequestInfo} is complete. If an
   * immediate shutdown is desired, the thread running the DequeuedRequestHandler should be interrupted.
   * <p/>
   * All {@link RestRequestInfo}s still in the queue will be left unhandled.
   */
  public void shutdownNow() {
    shutdownReady.set(true);
  }

  /**
   * Wait for the shutdown of this instance for the specified time.
   * @param timeout - the amount of time to wait for shutdown.
   * @param timeUnit - time unit of timeout
   * @return - {@code true} if shutdown succeeded within the timeout. {@code false} otherwise.
   * @throws InterruptedException - if the wait for shutdown was interrupted.
   */
  public boolean awaitShutdown(long timeout, TimeUnit timeUnit)
      throws InterruptedException {
    return shutdownLatch.await(timeout, timeUnit);
  }

  /**
   * Do tasks that are required to be done on completion of {@link RestRequestInfo} handling.
   * @param restRequestInfo - The {@link RestRequestInfo} whose handling completed.
   * @param e - If handling failed, the reason for failure. If handling succeeded, null.
   */
  private void onHandlingComplete(RestRequestInfo restRequestInfo, Exception e) {
    String uri = restRequestInfo.getRestRequestMetadata().getUri();
    logger.trace("Performing onComplete tasks for RestRequestInfo of request {}", uri);
    try {
      restRequestInfo.onComplete(e);
    } catch (Exception ee) {
      logger.error("Swallowing exception during onComplete tasks.", ee);
      restServerMetrics.dequeuedRequestHandlerHandlingCompleteTasksError.inc();
    } finally {
      RestResponseHandler responseHandler = restRequestInfo.getRestResponseHandler();
      if (e != null) {
        responseHandler.onRequestComplete(e, false);
      }
      if (responseHandler.isRequestComplete()) {
        logger.trace("Marking request {} as complete based on information from the RestResponseHandler", uri);
        onRequestComplete(restRequestInfo.getRestRequestMetadata());
      }
      logger.trace("Handling of RestRequestInfo of request {} completed", uri);
    }

  }

  /**
   * Empties the remaining {@link RestRequestInfo}s in the queue and releases resources held by them.
   * <p/>
   * The implementation releases {@link RestRequestContent} immediately if not null. Since multiple
   * {@link RestRequestInfo} might share the same {@link RestRequestMetadata}, it maintains a map of all seen
   * {@link RestRequestMetadata} and releases them at the end.
   */
  private void emptyQueue() {
    if (restRequestInfoQueue.size() > 0) {
      logger.warn("There were {} residual RestRequestInfos in queue during shutdown", restRequestInfoQueue.size());
      restServerMetrics.asyncRequestHandlerResidualQueueSize.inc(restRequestInfoQueue.size());
      RestRequestInfo residualRestRequestInfo = restRequestInfoQueue.poll();
      while (residualRestRequestInfo != null) {
        queuingTimeTracker.stopTracking(residualRestRequestInfo);
        if (residualRestRequestInfo.getRestRequestContent() != null) {
          residualRestRequestInfo.getRestRequestContent().release();
        }
        if (residualRestRequestInfo.isFirstPart()) {
          requestsInFlight.putIfAbsent(residualRestRequestInfo.getRestRequestMetadata(), true);
        }
        residualRestRequestInfo = restRequestInfoQueue.poll();
      }
    }
    logger.info("There were {} requests in flight when the AsyncRequestHandler was shut down", requestsInFlight.size());
    Iterator<Map.Entry<RestRequestMetadata, Boolean>> requestMetadata = requestsInFlight.entrySet().iterator();
    while (requestMetadata.hasNext()) {
      requestMetadata.next().getKey().release();
      requestMetadata.remove();
    }
  }

  /**
   * Tracks required metrics once the {@link RestRequestInfo} is dequeued.
   * @param restRequestInfo - the {@link RestRequestInfo} that was just dequeued.
   */
  private void trackMetricsOnDequeue(RestRequestInfo restRequestInfo) {
    Long queueTime = queuingTimeTracker.stopTracking(restRequestInfo);
    if (queueTime != null) {
      restServerMetrics.asyncRequestHandlerQueueTimeInMs.update(queueTime);
      logger.trace("Dequeued RestRequestInfo spent {} ms in the queue", queueTime);
    } else {
      logger.warn("Queueing time of RestRequestInfo was not tracked since queue start time was not recorded");
    }
    restServerMetrics.dequeuedRequestHandlerDequeuingRate.mark();
  }
}

/**
 * Used to gracefully shutdown a {@link DequeuedRequestHandler} instance. When this is processed by the
 * {@link DequeuedRequestHandler}, it shuts down.
 */
class PoisonInfo extends RestRequestInfo {
  public PoisonInfo() {
    super(null, null, null);
  }
}

/**
 * Used to track the time a particular {@link RestRequestInfo} spends being queued.
 */
class QueuingTimeTracker {
  private final ConcurrentHashMap<RestRequestInfo, Long> queueTimes = new ConcurrentHashMap<RestRequestInfo, Long>();
  private final Logger logger = LoggerFactory.getLogger(getClass());

  /**
   * Starts tracking the time spent being queued.
   * @param restRequestInfo - the {@link RestRequestInfo} whose queueing time needs to be tracked.
   */
  public void startTracking(RestRequestInfo restRequestInfo) {
    Long queueStartTime = System.currentTimeMillis();
    Long prevStartTime = queueTimes.putIfAbsent(restRequestInfo, queueStartTime);
    if (prevStartTime != null) {
      logger.warn("Duplicate request to track a RestRequestInfo. Prev track start time - {}, current time - {}",
          restRequestInfo, prevStartTime, queueStartTime);
    }
  }

  /**
   * Stops tracking the time elapsed since {@link #startTracking(RestRequestInfo)} was called on this
   * {@link RestRequestInfo} and returns it. If {@link #startTracking(RestRequestInfo)} was never called on this
   * {@link RestRequestInfo}, returns null.
   * @param restRequestInfo - the {@link RestRequestInfo} whose queueing time tracking needs to be stopped and recorded.
   * @return - time elapsed since {@link #startTracking(RestRequestInfo)} was called on the {@link RestRequestInfo}. If
   * {@link #startTracking(RestRequestInfo)} was never called, returns null.
   */
  public Long stopTracking(RestRequestInfo restRequestInfo) {
    Long queueStartTime = queueTimes.remove(restRequestInfo);
    return queueStartTime != null ? System.currentTimeMillis() - queueStartTime : null;
  }
}
