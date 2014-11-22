/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.picasso;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.widget.ImageView;
import android.widget.RemoteViews;
import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static com.squareup.picasso.Action.RequestWeakReference;
import static com.squareup.picasso.Dispatcher.HUNTER_BATCH_COMPLETE;
import static com.squareup.picasso.Dispatcher.REQUEST_BATCH_RESUME;
import static com.squareup.picasso.Dispatcher.REQUEST_GCED;
import static com.squareup.picasso.Picasso.LoadedFrom.MEMORY;
import static com.squareup.picasso.Utils.OWNER_MAIN;
import static com.squareup.picasso.Utils.THREAD_PREFIX;
import static com.squareup.picasso.Utils.VERB_CANCELED;
import static com.squareup.picasso.Utils.VERB_COMPLETED;
import static com.squareup.picasso.Utils.VERB_ERRORED;
import static com.squareup.picasso.Utils.VERB_RESUMED;
import static com.squareup.picasso.Utils.checkMain;
import static com.squareup.picasso.Utils.log;

/**
 * Image downloading, transformation, and caching manager.
 * 图片下载，transformation，已经cache的管理器
 * <p>
 * Use {@link #with(android.content.Context)} for the global singleton instance or construct your
 * own instance with {@link Builder}.
 */
public class Picasso {

  /** Callbacks for Picasso events. */
  public interface Listener {
    /**
     * Invoked when an image has failed to load. This is useful for reporting image failures to a
     * remote analytics service, for example.
     */
    void onImageLoadFailed(Picasso picasso, Uri uri, Exception exception);
  }

  /**
   * A transformer that is called immediately before every request is submitted. This can be used to
   * modify any information about a request.
   * 请求在提交之前，会立刻调用一个transformer。这个可以用来一个请求的任何信息
   * <p>
   * For example, if you use a CDN you can change the hostname for the image based on the current
   * location of the user in order to get faster download speeds.
   * <p>
   * <b>NOTE:</b> This is a beta feature. The API is subject to change in a backwards incompatible
   * way at any time.
   */
  public interface RequestTransformer {
    /**
     * Transform a request before it is submitted to be processed.
     *
     * @return The original request or a new request to replace it. Must not be null.
     */
    Request transformRequest(Request request);

    /** A {@link RequestTransformer} which returns the original request. */
    /** 返回原始版本的Request */
    RequestTransformer IDENTITY = new RequestTransformer() {
      @Override public Request transformRequest(Request request) {
        return request;
      }
    };
  }

  /**
   * The priority of a request.
   * 请求的优先级
   * @see RequestCreator#priority(Priority)
   */
  public enum Priority {
    LOW,
    NORMAL,
    HIGH
  }

  static final String TAG = "Picasso";

    /**
     * 分发结果的Handler，需要注意的是，该HANDLER是运行在主线程之上的。
     */
  static final Handler HANDLER = new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) {
      switch (msg.what) {
        case HUNTER_BATCH_COMPLETE: {
            //此处应该是和Volley一样，打包处理的图形库，因为这些图片可能地址以及大小都是一样的所有打包在一起进行处理。
          @SuppressWarnings("unchecked") List<BitmapHunter> batch = (List<BitmapHunter>) msg.obj;
          //noinspection ForLoopReplaceableByForEach
            //依次进行分发
          for (int i = 0, n = batch.size(); i < n; i++) {
            BitmapHunter hunter = batch.get(i);
            hunter.picasso.complete(hunter);
          }
          break;
        }
        case REQUEST_GCED: {
            //此处根据意思应该是请求被GC掉了
          Action action = (Action) msg.obj;
          if (action.getPicasso().loggingEnabled) {
            log(OWNER_MAIN, VERB_CANCELED, action.request.logId(), "target got garbage collected");
          }
            //那么就取消相应的处理已经被GC对应的请求
          action.picasso.cancelExistingRequest(action.getTarget());
          break;
        }
        case REQUEST_BATCH_RESUME:
            //请求被恢复了，还不太懂其业务逻辑。
          @SuppressWarnings("unchecked") List<Action> batch = (List<Action>) msg.obj;
          for (int i = 0, n = batch.size(); i < n; i++) {
            Action action = batch.get(i);
            action.picasso.resumeAction(action);
          }
          break;
        default:
          throw new AssertionError("Unknown handler message received: " + msg.what);
      }
    }
  };

    //单例模式
  static Picasso singleton = null;

  private final Listener listener;
  private final RequestTransformer requestTransformer;
  private final CleanupThread cleanupThread;
  private final List<RequestHandler> requestHandlers;

  final Context context;
  final Dispatcher dispatcher;
  final Cache cache;
  final Stats stats;
  final Map<Object, Action> targetToAction;
  final Map<ImageView, DeferredRequestCreator> targetToDeferredRequestCreator;
  final ReferenceQueue<Object> referenceQueue;

  boolean indicatorsEnabled;
  volatile boolean loggingEnabled;

  boolean shutdown;

  Picasso(Context context, Dispatcher dispatcher, Cache cache, Listener listener,
      RequestTransformer requestTransformer, List<RequestHandler> extraRequestHandlers,
      Stats stats, boolean indicatorsEnabled, boolean loggingEnabled) {
    this.context = context;
    this.dispatcher = dispatcher;
    this.cache = cache;
    this.listener = listener;
    this.requestTransformer = requestTransformer;

    //Picasso内置了总共7种处理器
    int builtInHandlers = 7; // Adjust this as internal handlers are added or removed.
    int extraCount = (extraRequestHandlers != null ? extraRequestHandlers.size() : 0);
    List<RequestHandler> allRequestHandlers =
        new ArrayList<RequestHandler>(builtInHandlers + extraCount);
    
    // ResourceRequestHandler needs to be the first in the list to avoid
    // forcing other RequestHandlers to perform null checks on request.uri
    // to cover the (request.resourceId != 0) case.
    // ResourceRequestHandler必须放于队列的队首，以避免其他的ReqeustHandler会检查reqeust.uri是否为null来覆盖掉reqeust.resourceId!=0
    allRequestHandlers.add(new ResourceRequestHandler(context));
    if (extraRequestHandlers != null) {
      allRequestHandlers.addAll(extraRequestHandlers);
    }
    allRequestHandlers.add(new ContactsPhotoRequestHandler(context));
    allRequestHandlers.add(new MediaStoreRequestHandler(context));
    allRequestHandlers.add(new ContentStreamRequestHandler(context));
    allRequestHandlers.add(new AssetRequestHandler(context));
    allRequestHandlers.add(new FileRequestHandler(context));
    allRequestHandlers.add(new NetworkRequestHandler(dispatcher.downloader, stats));
    //将所有的handler变为unmodifiedaleList
    requestHandlers = Collections.unmodifiableList(allRequestHandlers);

    this.stats = stats;
    //此处用了WeakHashMap来保存，避免OOM
    this.targetToAction = new WeakHashMap<Object, Action>();
    //此处用了WeakHashMap来保存，避免OOM
    this.targetToDeferredRequestCreator = new WeakHashMap<ImageView, DeferredRequestCreator>();
    this.indicatorsEnabled = indicatorsEnabled;
    this.loggingEnabled = loggingEnabled;
    this.referenceQueue = new ReferenceQueue<Object>();
    this.cleanupThread = new CleanupThread(referenceQueue, HANDLER);
    this.cleanupThread.start();
  }

  /** Cancel any existing requests for the specified target {@link ImageView}. */
  public void cancelRequest(ImageView view) {
    cancelExistingRequest(view);
  }

  /** Cancel any existing requests for the specified {@link Target} instance. */
  public void cancelRequest(Target target) {
    cancelExistingRequest(target);
  }

  /**
   * Cancel any existing requests for the specified {@link RemoteViews} target with the given {@code
   * viewId}.
   */
  public void cancelRequest(RemoteViews remoteViews, int viewId) {
    cancelExistingRequest(new RemoteViewsAction.RemoteViewsTarget(remoteViews, viewId));
  }

  /**
   * Cancel any existing requests with given tag. You can set a tag
   * on new requests with {@link RequestCreator#tag(Object)}.
   *
   * @see RequestCreator#tag(Object)
   */
  public void cancelTag(Object tag) {
    checkMain();
    List<Action> actions = new ArrayList<Action>(targetToAction.values());
    for (int i = 0, n = actions.size(); i < n; i++) {
      Action action = actions.get(i);
      if (action.getTag().equals(tag)) {
        cancelExistingRequest(action.getTarget());
      }
    }
  }

  /**
   * Pause existing requests with the given tag. Use {@link #resumeTag(Object)}
   * to resume requests with the given tag.
   *
   * @see #resumeTag(Object)
   * @see RequestCreator#tag(Object)
   */
  public void pauseTag(Object tag) {
    dispatcher.dispatchPauseTag(tag);
  }

  /**
   * Resume paused requests with the given tag. Use {@link #pauseTag(Object)}
   * to pause requests with the given tag.
   *
   * @see #pauseTag(Object)
   * @see RequestCreator#tag(Object)
   */
  public void resumeTag(Object tag) {
    dispatcher.dispatchResumeTag(tag);
  }

  /**
   * Start an image request using the specified URI.
   * <p>
   * Passing {@code null} as a {@code uri} will not trigger any request but will set a placeholder,
   * if one is specified.
   *
   * @see #load(File)
   * @see #load(String)
   * @see #load(int)
   */
  public RequestCreator load(Uri uri) {
    return new RequestCreator(this, uri, 0);
  }

  /**
   * Start an image request using the specified path. This is a convenience method for calling
   * {@link #load(Uri)}.
   * 使用一个自定的路径来启动一个image的reqeust。
   * <p>
   * This path may be a remote URL, file resource (prefixed with {@code file:}), content resource
   * (prefixed with {@code content:}), or android resource (prefixed with {@code
   * android.resource:}.
   * 路径可能是一个URL，或者是文件资源，内容资源，甚至是Android的资源文件。
   * <p>
   * Passing {@code null} as a {@code path} will not trigger any request but will set a
   * placeholder, if one is specified.
   * 如果传递的null的话，不会启动任何的请求，但会设置placeholder。
   *
   * @see #load(Uri)
   * @see #load(File)
   * @see #load(int)
   * @throws IllegalArgumentException if {@code path} is empty or blank string.
   */
  public RequestCreator load(String path) {
    if (path == null) {
      return new RequestCreator(this, null, 0);
    }
    if (path.trim().length() == 0) {
      throw new IllegalArgumentException("Path must not be empty.");
    }
    return load(Uri.parse(path));
  }

  /**
   * Start an image request using the specified image file. This is a convenience method for
   * calling {@link #load(Uri)}.
   * <p>
   * Passing {@code null} as a {@code file} will not trigger any request but will set a
   * placeholder, if one is specified.
   * <p>
   * Equivalent to calling {@link #load(Uri) load(Uri.fromFile(file))}.
   *
   * @see #load(Uri)
   * @see #load(String)
   * @see #load(int)
   */
  public RequestCreator load(File file) {
    if (file == null) {
      return new RequestCreator(this, null, 0);
    }
    return load(Uri.fromFile(file));
  }

  /**
   * Start an image request using the specified drawable resource ID.
   *
   * @see #load(Uri)
   * @see #load(String)
   * @see #load(File)
   */
  public RequestCreator load(int resourceId) {
    if (resourceId == 0) {
      throw new IllegalArgumentException("Resource ID must not be zero.");
    }
    return new RequestCreator(this, null, resourceId);
  }

  /**
   * {@code true} if debug display, logging, and statistics are enabled.
   * <p>
   * @deprecated Use {@link #areIndicatorsEnabled()} and {@link #isLoggingEnabled()} instead.
   */
  @SuppressWarnings("UnusedDeclaration") @Deprecated public boolean isDebugging() {
    return areIndicatorsEnabled() && isLoggingEnabled();
  }

  /**
   * Toggle whether debug display, logging, and statistics are enabled.
   * <p>
   * @deprecated Use {@link #setIndicatorsEnabled(boolean)} and {@link #setLoggingEnabled(boolean)}
   * instead.
   */
  @SuppressWarnings("UnusedDeclaration") @Deprecated public void setDebugging(boolean debugging) {
    setIndicatorsEnabled(debugging);
  }

  /** Toggle whether to display debug indicators on images. */
  @SuppressWarnings("UnusedDeclaration") public void setIndicatorsEnabled(boolean enabled) {
    indicatorsEnabled = enabled;
  }

  /** {@code true} if debug indicators should are displayed on images. */
  @SuppressWarnings("UnusedDeclaration") public boolean areIndicatorsEnabled() {
    return indicatorsEnabled;
  }

  /**
   * Toggle whether debug logging is enabled.
   * <p>
   * <b>WARNING:</b> Enabling this will result in excessive object allocation. This should be only
   * be used for debugging Picasso behavior. Do NOT pass {@code BuildConfig.DEBUG}.
   */
  public void setLoggingEnabled(boolean enabled) {
    loggingEnabled = enabled;
  }

  /** {@code true} if debug logging is enabled. */
  public boolean isLoggingEnabled() {
    return loggingEnabled;
  }

  /**
   * Creates a {@link StatsSnapshot} of the current stats for this instance.
   * <p>
   * <b>NOTE:</b> The snapshot may not always be completely up-to-date if requests are still in
   * progress.
   */
  @SuppressWarnings("UnusedDeclaration") public StatsSnapshot getSnapshot() {
    return stats.createSnapshot();
  }

  /** Stops this instance from accepting further requests. */
  public void shutdown() {
    if (this == singleton) {
      throw new UnsupportedOperationException("Default singleton instance cannot be shutdown.");
    }
    if (shutdown) {
      return;
    }
    cache.clear();
    cleanupThread.shutdown();
    stats.shutdown();
    dispatcher.shutdown();
    for (DeferredRequestCreator deferredRequestCreator : targetToDeferredRequestCreator.values()) {
      deferredRequestCreator.cancel();
    }
    targetToDeferredRequestCreator.clear();
    shutdown = true;
  }

  List<RequestHandler> getRequestHandlers() {
    return requestHandlers;
  }

  Request transformRequest(Request request) {
    Request transformed = requestTransformer.transformRequest(request);
    if (transformed == null) {
      throw new IllegalStateException("Request transformer "
          + requestTransformer.getClass().getCanonicalName()
          + " returned null for "
          + request);
    }
    return transformed;
  }

  void defer(ImageView view, DeferredRequestCreator request) {
    targetToDeferredRequestCreator.put(view, request);
  }

  void enqueueAndSubmit(Action action) {
    Object target = action.getTarget();
    if (target != null && targetToAction.get(target) != action) {
      // This will also check we are on the main thread.
      cancelExistingRequest(target);
      targetToAction.put(target, action);
    }
    submit(action);
  }

  void submit(Action action) {
    dispatcher.dispatchSubmit(action);
  }

  Bitmap quickMemoryCacheCheck(String key) {
    Bitmap cached = cache.get(key);
    if (cached != null) {
      stats.dispatchCacheHit();
    } else {
      stats.dispatchCacheMiss();
    }
    return cached;
  }

  void complete(BitmapHunter hunter) {
    Action single = hunter.getAction();
    List<Action> joined = hunter.getActions();

    boolean hasMultiple = joined != null && !joined.isEmpty();
    boolean shouldDeliver = single != null || hasMultiple;

    if (!shouldDeliver) {
      return;
    }

    Uri uri = hunter.getData().uri;
    Exception exception = hunter.getException();
    Bitmap result = hunter.getResult();
    LoadedFrom from = hunter.getLoadedFrom();

    if (single != null) {
      deliverAction(result, from, single);
    }

    if (hasMultiple) {
      //noinspection ForLoopReplaceableByForEach
      for (int i = 0, n = joined.size(); i < n; i++) {
        Action join = joined.get(i);
        deliverAction(result, from, join);
      }
    }

    if (listener != null && exception != null) {
      listener.onImageLoadFailed(this, uri, exception);
    }
  }

  void resumeAction(Action action) {
    Bitmap bitmap = null;
    if (!action.skipCache) {
      bitmap = quickMemoryCacheCheck(action.getKey());
    }

    if (bitmap != null) {
      // Resumed action is cached, complete immediately.
      deliverAction(bitmap, MEMORY, action);
      if (loggingEnabled) {
        log(OWNER_MAIN, VERB_COMPLETED, action.request.logId(), "from " + MEMORY);
      }
    } else {
      // Re-submit the action to the executor.
      enqueueAndSubmit(action);
      if (loggingEnabled) {
        log(OWNER_MAIN, VERB_RESUMED, action.request.logId());
      }
    }
  }

  private void deliverAction(Bitmap result, LoadedFrom from, Action action) {
    if (action.isCancelled()) {
      return;
    }
    if (!action.willReplay()) {
      targetToAction.remove(action.getTarget());
    }
    if (result != null) {
      if (from == null) {
        throw new AssertionError("LoadedFrom cannot be null.");
      }
      action.complete(result, from);
      if (loggingEnabled) {
        log(OWNER_MAIN, VERB_COMPLETED, action.request.logId(), "from " + from);
      }
    } else {
      action.error();
      if (loggingEnabled) {
        log(OWNER_MAIN, VERB_ERRORED, action.request.logId());
      }
    }
  }

  private void cancelExistingRequest(Object target) {
    checkMain();
    Action action = targetToAction.remove(target);
    if (action != null) {
      action.cancel();
      dispatcher.dispatchCancel(action);
    }
    if (target instanceof ImageView) {
      ImageView targetImageView = (ImageView) target;
      DeferredRequestCreator deferredRequestCreator =
          targetToDeferredRequestCreator.remove(targetImageView);
      if (deferredRequestCreator != null) {
        deferredRequestCreator.cancel();
      }
    }
  }

  private static class CleanupThread extends Thread {
    private final ReferenceQueue<?> referenceQueue;
    private final Handler handler;

    CleanupThread(ReferenceQueue<?> referenceQueue, Handler handler) {
      this.referenceQueue = referenceQueue;
      this.handler = handler;
      setDaemon(true);
      setName(THREAD_PREFIX + "refQueue");
    }

    @Override public void run() {
      Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND);
      while (true) {
        try {
          RequestWeakReference<?> remove = (RequestWeakReference<?>) referenceQueue.remove();
          handler.sendMessage(handler.obtainMessage(REQUEST_GCED, remove.action));
        } catch (InterruptedException e) {
          break;
        } catch (final Exception e) {
          handler.post(new Runnable() {
            @Override public void run() {
              throw new RuntimeException(e);
            }
          });
          break;
        }
      }
    }

    void shutdown() {
      interrupt();
    }
  }

  /**
   * The global default {@link Picasso} instance.
   * 全局默认的实例
   * <p>
   * This instance is automatically initialized with defaults that are suitable to most
   * implementations.
   * 该实例默认初始化了对大多数情况下最好的参数。
   * <ul>
   * <li>LRU memory cache of 15% the available application RAM -- LRU内存缓存所占的空间为应用程序RAM的15%</li>
   * <li>Disk cache of 2% storage space up to 50MB but no less than 5MB. (Note: this is only
   * available on API 14+ <em>or</em> if you are using a standalone library that provides a disk
   * cache on all API levels like OkHttp) 磁盘的缓存为磁盘空间的2%，但小于50MB，大于5MB，但需要注意的是，该设置
   * 仅仅在API大于14，即Android4.0以上，在API层次使用标准库才可以支持</li>
   * <li>Three download threads for disk and network access.使用3个下载线程来访问磁盘或者网络</li>
   * </ul>
   * <p>
   * If these settings do not meet the requirements of your application you can construct your own
   * with full control over the configuration by using {@link Picasso.Builder} to create a
   * {@link Picasso} instance. You can either use this directly or by setting it as the global
   * instance with {@link #setSingletonInstance}.
   * 如果这些设置不能满足我们的应用程序的使用需求的话，那么我们可以通过Picasso.Builder这个构造者模式来配置一个Picasso的实例。
   * 我们可以直接使用这个实例，或者通过setSingletonInstance来设置这个实例为全局的实例。
   */
  public static Picasso with(Context context) {
    if (singleton == null) {
      synchronized (Picasso.class) {
        if (singleton == null) {
          singleton = new Builder(context).build();
        }
      }
    }
    return singleton;
  }

  /**
   * Set the global instance returned from {@link #with}.
   * 设置唯一的Picasso的唯一实例。
   * <p>
   * This method must be called before any calls to {@link #with} and may only be called once.
   */
  public static void setSingletonInstance(Picasso picasso) {
    synchronized (Picasso.class) {
        //注意此处也是同步的，只要是设置全局Picasso的地方，都使用Picasso来保护
      if (singleton != null) {
        throw new IllegalStateException("Singleton instance already exists.");
      }
      singleton = picasso;
    }
  }

  /** Fluent API for creating {@link Picasso} instances. */
  @SuppressWarnings("UnusedDeclaration") // Public API.
  public static class Builder {
    private final Context context;
    private Downloader downloader;
    private ExecutorService service;
    private Cache cache;
    private Listener listener;
    private RequestTransformer transformer;
    private List<RequestHandler> requestHandlers;

    private boolean indicatorsEnabled;
    private boolean loggingEnabled;

    /** Start building a new {@link Picasso} instance. 创建一个Picasso的实例 */
    public Builder(Context context) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      //注意，此处获取的上下文Context是应用程序Application的上下文，而非Activity的上下文，这样创建的Pisasso可以在整个应用
      //程序的的生命周期中都可以使用，与Volley是一致的。
      this.context = context.getApplicationContext();
    }

    /** Specify the {@link Downloader} that will be used for downloading images. 设定用于下载的Downloader*/
    public Builder downloader(Downloader downloader) {
      if (downloader == null) {
        throw new IllegalArgumentException("Downloader must not be null.");
      }
      if (this.downloader != null) {
        throw new IllegalStateException("Downloader already set.");
      }
      this.downloader = downloader;
      return this;
    }

    /**
     * Specify the executor service for loading images in the background.
     * 设定用来在后台加载图片的线程池
     * <p>
     * Note: Calling {@link Picasso#shutdown() shutdown()} will not shutdown supplied executors.
     */
    public Builder executor(ExecutorService executorService) {
      if (executorService == null) {
        throw new IllegalArgumentException("Executor service must not be null.");
      }
      if (this.service != null) {
        throw new IllegalStateException("Executor service already set.");
      }
      this.service = executorService;
      return this;
    }

    /** Specify the memory cache used for the most recent images.设置用于缓存最近使用到图片的内存库 */
    public Builder memoryCache(Cache memoryCache) {
      if (memoryCache == null) {
        throw new IllegalArgumentException("Memory cache must not be null.");
      }
      if (this.cache != null) {
        throw new IllegalStateException("Memory cache already set.");
      }
      this.cache = memoryCache;
      return this;
    }

    /** Specify a listener for interesting events. 回调接口 */
    public Builder listener(Listener listener) {
      if (listener == null) {
        throw new IllegalArgumentException("Listener must not be null.");
      }
      if (this.listener != null) {
        throw new IllegalStateException("Listener already set.");
      }
      this.listener = listener;
      return this;
    }

    /**
     * Specify a transformer for all incoming requests.
     * 对进来的请求的一个预处理器。
     * <p>
     * <b>NOTE:</b> This is a beta feature. The API is subject to change in a backwards incompatible
     * way at any time.
     */
    public Builder requestTransformer(RequestTransformer transformer) {
      if (transformer == null) {
        throw new IllegalArgumentException("Transformer must not be null.");
      }
      if (this.transformer != null) {
        throw new IllegalStateException("Transformer already set.");
      }
      this.transformer = transformer;
      return this;
    }

    /** Register a {@link RequestHandler}.注册一个Reqeust的Handler，将Handler放进所有的reqeust当中*/
    public Builder addRequestHandler(RequestHandler requestHandler) {
      if (requestHandler == null) {
        throw new IllegalArgumentException("RequestHandler must not be null.");
      }
      if (requestHandlers == null) {
        requestHandlers = new ArrayList<RequestHandler>();
      }
      if (requestHandlers.contains(requestHandler)) {
        throw new IllegalStateException("RequestHandler already registered.");
      }
      requestHandlers.add(requestHandler);
      return this;
    }

    /**
     * @deprecated Use {@link #indicatorsEnabled(boolean)} instead.
     * Whether debugging is enabled or not.
     */
    @Deprecated public Builder debugging(boolean debugging) {
      return indicatorsEnabled(debugging);
    }

    /** Toggle whether to display debug indicators on images. */
    public Builder indicatorsEnabled(boolean enabled) {
      this.indicatorsEnabled = enabled;
      return this;
    }

    /**
     * Toggle whether debug logging is enabled. log开关
     * <p>
     * <b>WARNING:</b> Enabling this will result in excessive object allocation. This should be only
     * be used for debugging purposes. Do NOT pass {@code BuildConfig.DEBUG}. 仅仅用于调试阶段，因为log会导致过度的资源消耗。
     */
    public Builder loggingEnabled(boolean enabled) {
      this.loggingEnabled = enabled;
      return this;
    }

    /** Create the {@link Picasso} instance. 创建Picasso的实例 */
    public Picasso build() {
      Context context = this.context;

      if (downloader == null) {
        downloader = Utils.createDefaultDownloader(context);
      }
      if (cache == null) {
        cache = new LruCache(context);
      }
      if (service == null) {
        service = new PicassoExecutorService();
      }
      if (transformer == null) {
        transformer = RequestTransformer.IDENTITY;
      }

      Stats stats = new Stats(cache);

      Dispatcher dispatcher = new Dispatcher(context, service, HANDLER, downloader, cache, stats);

      return new Picasso(context, dispatcher, cache, listener, transformer,
          requestHandlers, stats, indicatorsEnabled, loggingEnabled);
    }
  }

  /** Describes where the image was loaded from. */
  public enum LoadedFrom {
    MEMORY(Color.GREEN),
    DISK(Color.YELLOW),
    NETWORK(Color.RED);

    final int debugColor;

    private LoadedFrom(int debugColor) {
      this.debugColor = debugColor;
    }
  }
}
