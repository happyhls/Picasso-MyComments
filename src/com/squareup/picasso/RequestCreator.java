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

import android.app.Notification;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.RemoteViews;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static com.squareup.picasso.BitmapHunter.forRequest;
import static com.squareup.picasso.Picasso.LoadedFrom.MEMORY;
import static com.squareup.picasso.Picasso.Priority;
import static com.squareup.picasso.PicassoDrawable.setBitmap;
import static com.squareup.picasso.PicassoDrawable.setPlaceholder;
import static com.squareup.picasso.RemoteViewsAction.AppWidgetAction;
import static com.squareup.picasso.RemoteViewsAction.NotificationAction;
import static com.squareup.picasso.Utils.OWNER_MAIN;
import static com.squareup.picasso.Utils.VERB_CHANGED;
import static com.squareup.picasso.Utils.VERB_COMPLETED;
import static com.squareup.picasso.Utils.VERB_CREATED;
import static com.squareup.picasso.Utils.checkMain;
import static com.squareup.picasso.Utils.checkNotMain;
import static com.squareup.picasso.Utils.createKey;
import static com.squareup.picasso.Utils.isMain;
import static com.squareup.picasso.Utils.log;
import static com.squareup.picasso.Utils.sneakyRethrow;

/** Fluent API for building an image download request. */
/** 请求的构造者 */
@SuppressWarnings("UnusedDeclaration") // Public API.
public class RequestCreator {
  private static int nextId = 0;

  //此处是设置该Reqeust的Id，有两种情况：
  //如果是在主线程上调用：则直接将返回nextId，并自增-->貌似不是线程安全的
  //如果是在其他的线程上调用，那么通过handler发送给主线程，在主线程中同样从这里取一个nextId,然后自增，然后再设置给AtomicInteger。
  //使用了CountDownLatch来在其他线程中等待主线程设置Id。
  //有个问题：这个线程是不是线程安全的？这个应该是线程安全的，因为这个方法在返回nextId之前，保证调用是来自主线程。换句话说，只有在主线程调用getRequestId()的时候，才会去生成并返回reqeustId，既然都在一个线程上进行的，那肯定是线程安全的。
  private static int getRequestId() {
    if (isMain()) {
      return nextId++;
    }

    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicInteger id = new AtomicInteger();
    Picasso.HANDLER.post(new Runnable() {
      @Override public void run() {
        id.set(getRequestId());
        latch.countDown();
      }
    });
    try {
      latch.await();
    } catch (InterruptedException e) {
      sneakyRethrow(e);
    }
    return id.get();
  }

  /**
   * ReqeustCreator中保存了picasso的相关信息，并且将数据信息(即和图片相关，但和处理逻辑不相关的信息，比如地址，大小，是否要旋转缩放等等)都保存在了Reqeust.Builder当中
   * 同时，该类还保存了一些用来数据采集流程的相关的信息：
   * 比如：是否要跳过内存的cache，是否要设置默认显示的图片，出错的图片，等等。
   */
  private final Picasso picasso;
  
  //注意，其实在Reqeust.Builder是保存了所有的与图片显示相关的信息，包括图片的URL或者ResourceId，宽度，长度，是否旋转裁剪等等。
  private final Request.Builder data;

  private boolean skipMemoryCache;
  private boolean noFade;
  private boolean deferred;
  private boolean setPlaceholder = true;
  private int placeholderResId;
  private int errorResId;
  private Drawable placeholderDrawable;
  private Drawable errorDrawable;
  private Object tag;

  RequestCreator(Picasso picasso, Uri uri, int resourceId) {
    if (picasso.shutdown) {
      throw new IllegalStateException(
          "Picasso instance already shut down. Cannot submit new requests.");
    }
    this.picasso = picasso;
    this.data = new Request.Builder(uri, resourceId);
  }

  RequestCreator() {
    this.picasso = null;
    this.data = new Request.Builder(null, 0);
  }

  /**
   * Explicitly opt-out to having a placeholder set when calling {@code into}.
   * 为调用{@code into}的时候明确指出去掉占位符。
   * <p>
   * By default, Picasso will either set a supplied placeholder or clear the target
   * {@link ImageView} in order to ensure behavior in situations where views are recycled. This
   * method will prevent that behavior and retain any already set image.
   * 默认情况下，Picasso会设置一个准备好的占位符，或者是清空target{@link ImageView}来确保可能出现的view需要被回收的情况。
   * 该方法会明确禁止这个现象，并且户保留那些已经设置过的图片。
   */
  public RequestCreator noPlaceholder() {
    if (placeholderResId != 0) {
      throw new IllegalStateException("Placeholder resource already set.");
    }
    if (placeholderDrawable != null) {
      throw new IllegalStateException("Placeholder image already set.");
    }
    setPlaceholder = false;
    return this;
  }

  /**
   * A placeholder drawable to be used while the image is being loaded. If the requested image is
   * not immediately available in the memory cache then this resource will be set on the target
   * {@link ImageView}.
   * 占位符图片用来在image加载的时候使用。如果说请求的图片无法从memory cache中直接获取的话，那么就会设置target{@link ImageView}显示
   * placeholderResId指定的图片。
   */
  public RequestCreator placeholder(int placeholderResId) {
    if (!setPlaceholder) {
      throw new IllegalStateException("Already explicitly declared as no placeholder.");
    }
    if (placeholderResId == 0) {
      throw new IllegalArgumentException("Placeholder image resource invalid.");
    }
    if (placeholderDrawable != null) {
      throw new IllegalStateException("Placeholder image already set.");
    }
    this.placeholderResId = placeholderResId;
    return this;
  }

  /**
   * A placeholder drawable to be used while the image is being loaded. If the requested image is
   * not immediately available in the memory cache then this resource will be set on the target
   * {@link ImageView}.
   * 占位符图片用来在image加载的时候使用。如果说请求的图片无法从memory cache中直接获取的话，那么就会设置target{@link ImageView}显示
   * placeholderDrawable的图片。
   * <p>
   * If you are not using a placeholder image but want to clear an existing image (such as when
   * used in an {@link android.widget.Adapter adapter}), pass in {@code null}.
   * 如果说我们并不使用placeholder的图像，但是想清空已经存在的图片，比如在使用{@link android.widget.Adapter adapter}的时候(因为这个时候，同一个控件是不断被复用的)，传递给他null就可以了。
   */
  public RequestCreator placeholder(Drawable placeholderDrawable) {
    if (!setPlaceholder) {
      throw new IllegalStateException("Already explicitly declared as no placeholder.");
    }
    if (placeholderResId != 0) {
      throw new IllegalStateException("Placeholder image already set.");
    }
    this.placeholderDrawable = placeholderDrawable;
    return this;
  }

  /** An error drawable to be used if the request image could not be loaded. */
  public RequestCreator error(int errorResId) {
    if (errorResId == 0) {
      throw new IllegalArgumentException("Error image resource invalid.");
    }
    if (errorDrawable != null) {
      throw new IllegalStateException("Error image already set.");
    }
    this.errorResId = errorResId;
    return this;
  }

  /** An error drawable to be used if the request image could not be loaded. */
  public RequestCreator error(Drawable errorDrawable) {
    if (errorDrawable == null) {
      throw new IllegalArgumentException("Error image may not be null.");
    }
    if (errorResId != 0) {
      throw new IllegalStateException("Error image already set.");
    }
    this.errorDrawable = errorDrawable;
    return this;
  }

  /**
   * Assign a tag to this request. Tags are an easy way to logically associate
   * related requests that can be managed together e.g. paused, resumed,
   * or canceled.
   * 设置一个与reqeust相关联的tag。Tags是一种比较简单的实现的与reqeust相关联的实现方式，可以用来实现paused，resumed，或者取消。
   * <p>
   * You can either use simple {@link String} tags or objects that naturally
   * define the scope of your requests within your app such as a
   * {@link android.content.Context}, an {@link android.app.Activity}, or a
   * {@link android.app.Fragment}.
   * 我们可以使用{@link String}的tags或者objects(同时定义了请求在app内的scope范围。比如{@link android.content.Context}，
   * {@link android.app.Activity}或者{@link android.app.Fragment}）
   * 
   * <strong>WARNING:</strong>: Picasso will keep a reference to the tag for
   * as long as this tag is paused and/or has active requests. Look out for
   * potential leaks.
   * 注意，只要reqeusts处于paused或者active的状态，那么Picasso会保持对tag的引用，这可能会导致一些内存上的泄漏。
   * 
   * @see Picasso#cancelTag(Object)
   * @see Picasso#pauseTag(Object)
   * @see Picasso#resumeTag(Object)
   */
  public RequestCreator tag(Object tag) {
    if (tag == null) {
      throw new IllegalArgumentException("Tag invalid.");
    }
    if (this.tag != null) {
      throw new IllegalStateException("Tag already set.");
    }
    this.tag = tag;
    return this;
  }

  /**
   * Attempt to resize the image to fit exactly into the target {@link ImageView}'s bounds. This
   * will result in delayed execution of the request until the {@link ImageView} has been laid out.
   * 尝试修改image的尺寸使其尽可能准确的适用{@link ImageView}的边界。这可能导致对应的reqeust会一直拖后到{@link ImageView}
   * 已经展出界面之后才会开始运行。
   * <p>
   * <em>Note:</em> This method works only when your target is an {@link ImageView}.
   * 注意：该方法仅有在我们的target是{@link ImageView}的时候适用。
   */
  public RequestCreator fit() {
    deferred = true;
    return this;
  }

  /** Internal use only. Used by {@link DeferredRequestCreator}. */
  /** 仅仅内部使用，供 {@link DeferredRequestCreator}. */
  RequestCreator unfit() {
    deferred = false;
    return this;
  }

  /** Resize the image to the specified dimension size. */
  /** 将image的大小修改为指定的尺寸dimension */
  public RequestCreator resizeDimen(int targetWidthResId, int targetHeightResId) {
    Resources resources = picasso.context.getResources();
    int targetWidth = resources.getDimensionPixelSize(targetWidthResId);
    int targetHeight = resources.getDimensionPixelSize(targetHeightResId);
    return resize(targetWidth, targetHeight);
  }

  /** Resize the image to the specified size in pixels. */
  /** 将image的大小修改为指定的尺寸pixels */
  public RequestCreator resize(int targetWidth, int targetHeight) {
    data.resize(targetWidth, targetHeight);
    return this;
  }

  /**
   * Crops an image inside of the bounds specified by {@link #resize(int, int)} rather than
   * distorting the aspect ratio. This cropping technique scales the image so that it fills the
   * requested bounds and then crops the extra.
   */
  public RequestCreator centerCrop() {
    data.centerCrop();
    return this;
  }

  /**
   * Centers an image inside of the bounds specified by {@link #resize(int, int)}. This scales
   * the image so that both dimensions are equal to or less than the requested bounds.
   */
  public RequestCreator centerInside() {
    data.centerInside();
    return this;
  }

  /** Rotate the image by the specified degrees. */
  public RequestCreator rotate(float degrees) {
    data.rotate(degrees);
    return this;
  }

  /** Rotate the image by the specified degrees around a pivot point. */
  public RequestCreator rotate(float degrees, float pivotX, float pivotY) {
    data.rotate(degrees, pivotX, pivotY);
    return this;
  }

  /**
   * Attempt to decode the image using the specified config.
   * <p>
   * Note: This value may be ignored by {@link BitmapFactory}. See
   * {@link BitmapFactory.Options#inPreferredConfig its documentation} for more details.
   */
  public RequestCreator config(Bitmap.Config config) {
    data.config(config);
    return this;
  }

  /**
   * Sets the stable key for this request to be used instead of the URI or resource ID when
   * caching. Two requests with the same value are considered to be for the same resource.
   */
  public RequestCreator stableKey(String stableKey) {
    data.stableKey(stableKey);
    return this;
  }

  /**
   * Set the priority of this request.
   * 设置请求的优先级。
   * <p>
   * This will affect the order in which the requests execute but does not guarantee it.
   * By default, all requests have {@link Priority#NORMAL} priority, except for
   * {@link #fetch()} requests, which have {@link Priority#LOW} priority by default.
   * 这会导致reqeusts的执行顺序的改变，但并不会保证。默认情况下，所有的reqeusts有
   */
  public RequestCreator priority(Priority priority) {
    data.priority(priority);
    return this;
  }

  /**
   * Add a custom transformation to be applied to the image.
   * 添加一个图片上使用的transformation
   * <p>
   * Custom transformations will always be run after the built-in transformations.
   */
  // TODO show example of calling resize after a transform in the javadoc
  public RequestCreator transform(Transformation transformation) {
    data.transform(transformation);
    return this;
  }

  /**
   * Indicate that this action should not use the memory cache for attempting to load or save the
   * image. This can be useful when you know an image will only ever be used once (e.g., loading
   * an image from the filesystem and uploading to a remote server).
   * 标志着该action不要使用memory cache来尝试加载或者保存图片。这在image仅仅会使用到一次的时候是很有用的。(e.g
   * 从文件系统中加载图片并且上传到远程的server的时候)。
   */
  public RequestCreator skipMemoryCache() {
    skipMemoryCache = true;
    return this;
  }

  /** Disable brief fade in of images loaded from the disk cache or network. */
  /** 关闭该功能，具体什么表现，还不是特别明白 */
  public RequestCreator noFade() {
    noFade = true;
    return this;
  }

  /**
   * Synchronously fulfill this request. Must not be called from the main thread.
   * 同步填充request，必须从主线程上调用。
   * <p>
   * <em>Note</em>: The result of this operation is not cached in memory because the underlying
   * {@link Cache} implementation is not guaranteed to be thread-safe.
   * 该操作的结果并不会在内存中cache，因为底层的{@link Cache}实现并不保证是线程安全的。
   */
  public Bitmap get() throws IOException {
    long started = System.nanoTime();
    checkNotMain();

    if (deferred) {
      throw new IllegalStateException("Fit cannot be used with get.");
    }
    if (!data.hasImage()) {
      return null;
    }

    //创建Request
    Request finalData = createRequest(started);
    //创建CacheKey，这些属性都会用到
    String key = createKey(finalData, new StringBuilder());
    //获取对应的action
    Action action = new GetAction(picasso, finalData, skipMemoryCache, key, tag);
    //首先创建一个BitmapHunter，并执行hunt()方法XXXXX这个地方不明白，应该是异步的，但没看明白。
    return forRequest(picasso, picasso.dispatcher, picasso.cache, picasso.stats, action).hunt();
  }

  /**
   * Asynchronously fulfills the request without a {@link ImageView} or {@link Target}. This is
   * useful when you want to warm up the cache with an image.
   * <p>
   * <em>Note:</em> It is safe to invoke this method from any thread.
   */
  public void fetch() {
    long started = System.nanoTime();

    if (deferred) {
      throw new IllegalStateException("Fit cannot be used with fetch.");
    }
    if (data.hasImage()) {
      // Fetch requests have lower priority by default.
      if (!data.hasPriority()) {
        data.priority(Priority.LOW);
      }

      Request request = createRequest(started);
      String key = createKey(request, new StringBuilder());

      Action action = new FetchAction(picasso, request, skipMemoryCache, key, tag);
      picasso.submit(action);
    }
  }

  /**
   * Asynchronously fulfills the request into the specified {@link Target}. In most cases, you
   * should use this when you are dealing with a custom {@link android.view.View View} or view
   * holder which should implement the {@link Target} interface.
   * 一个异步方法：用来将request填充到指定的Target上。在大多数情况下，当我们需要处理一个自定义的View或者一个view holder的时候，
   * 需要继承Target接口。
   * 
   * <p>
   * Implementing on a {@link android.view.View View}:
   * 实现一个 {@link android.view.View View}:
   * <blockquote><pre>
   * public class ProfileView extends FrameLayout implements Target {
   *   {@literal @}Override public void onBitmapLoaded(Bitmap bitmap, LoadedFrom from) {
   *     setBackgroundDrawable(new BitmapDrawable(bitmap));
   *   }
   *
   *   {@literal @}Override public void onBitmapFailed() {
   *     setBackgroundResource(R.drawable.profile_error);
   *   }
   *
   *   {@literal @}Override public void onPrepareLoad(Drawable placeHolderDrawable) {
   *     frame.setBackgroundDrawable(placeHolderDrawable);
   *   }
   * }
   * </pre></blockquote>
   * Implementing on a view holder object for use inside of an adapter:
   * <blockquote><pre>
   * public class ViewHolder implements Target {
   *   public FrameLayout frame;
   *   public TextView name;
   *
   *   {@literal @}Override public void onBitmapLoaded(Bitmap bitmap, LoadedFrom from) {
   *     frame.setBackgroundDrawable(new BitmapDrawable(bitmap));
   *   }
   *
   *   {@literal @}Override public void onBitmapFailed() {
   *     frame.setBackgroundResource(R.drawable.profile_error);
   *   }
   *
   *   {@literal @}Override public void onPrepareLoad(Drawable placeHolderDrawable) {
   *     frame.setBackgroundDrawable(placeHolderDrawable);
   *   }
   * }
   * </pre></blockquote>
   * <p>
   * <em>Note:</em> This method keeps a weak reference to the {@link Target} instance and will be
   * garbage collected if you do not keep a strong reference to it. To receive callbacks when an
   * image is loaded use {@link #into(android.widget.ImageView, Callback)}.
   * 注意，该方法保持了一个对Target的weak弱引用，也就是说，如果我们其他的地方没有对这个保持引用的话，那么就会被垃圾回收。
   * 如果需要当一个图片加载成功之后，需要接收回调的话，我们应该使用into(android.widget.ImageView, Callback)方法。
   */
  public void into(Target target) {
    long started = System.nanoTime();
    checkMain();

    if (target == null) {
      throw new IllegalArgumentException("Target must not be null.");
    }
    if (deferred) {
      throw new IllegalStateException("Fit cannot be used with a Target.");
    }

    //如果请求没有设置图片属性，即既没有设置uri，也没有设置resourceId的时候，会取消该request
    if (!data.hasImage()) {
      picasso.cancelRequest(target);
      target.onPrepareLoad(setPlaceholder ? getPlaceholderDrawable() : null);
      return;
    }

    //创建一个请求
    Request request = createRequest(started);
    String requestKey = createKey(request);

    //如果设置不要跳过MemoryCache，那么就从MemoryCache中取回数据
    if (!skipMemoryCache) {
      //尝试从cache中获取图片
      Bitmap bitmap = picasso.quickMemoryCacheCheck(requestKey);
      //如果cache hit，那么则取消相应的请求，并从Memory返回图片
      if (bitmap != null) {
        picasso.cancelRequest(target);
        target.onBitmapLoaded(bitmap, MEMORY);
        return;
      }
    }

    //到此，图片没有cache hit，需要访问网络获取图片。
    //设置默认显示的图片
    target.onPrepareLoad(setPlaceholder ? getPlaceholderDrawable() : null);

    //根据需要获取的图片的相关信息，创建一个Action
    Action action =
        new TargetAction(picasso, target, request, skipMemoryCache, errorResId, errorDrawable,
            requestKey, tag);
    //将action添加到队列并提交。
    picasso.enqueueAndSubmit(action);
  }

  /**
   * Asynchronously fulfills the request into the specified {@link RemoteViews} object with the
   * given {@code viewId}. This is used for loading bitmaps into a {@link Notification}.
   */
  public void into(RemoteViews remoteViews, int viewId, int notificationId,
      Notification notification) {
    long started = System.nanoTime();
    checkMain();

    if (remoteViews == null) {
      throw new IllegalArgumentException("RemoteViews must not be null.");
    }
    if (notification == null) {
      throw new IllegalArgumentException("Notification must not be null.");
    }
    if (deferred) {
      throw new IllegalStateException("Fit cannot be used with RemoteViews.");
    }
    if (placeholderDrawable != null || placeholderResId != 0 || errorDrawable != null) {
      throw new IllegalArgumentException(
          "Cannot use placeholder or error drawables with remote views.");
    }

    Request request = createRequest(started);
    String key = createKey(request);

    RemoteViewsAction action =
        new NotificationAction(picasso, request, remoteViews, viewId, notificationId, notification,
            skipMemoryCache, errorResId, key, tag);

    performRemoteViewInto(action);
  }

  /**
   * Asynchronously fulfills the request into the specified {@link RemoteViews} object with the
   * given {@code viewId}. This is used for loading bitmaps into all instances of a widget.
   */
  public void into(RemoteViews remoteViews, int viewId, int[] appWidgetIds) {
    long started = System.nanoTime();
    checkMain();

    if (remoteViews == null) {
      throw new IllegalArgumentException("remoteViews must not be null.");
    }
    if (appWidgetIds == null) {
      throw new IllegalArgumentException("appWidgetIds must not be null.");
    }
    if (deferred) {
      throw new IllegalStateException("Fit cannot be used with remote views.");
    }
    if (placeholderDrawable != null || placeholderResId != 0 || errorDrawable != null) {
      throw new IllegalArgumentException(
          "Cannot use placeholder or error drawables with remote views.");
    }

    Request request = createRequest(started);
    String key = createKey(request);

    RemoteViewsAction action =
        new AppWidgetAction(picasso, request, remoteViews, viewId, appWidgetIds, skipMemoryCache,
            errorResId, key, tag);

    performRemoteViewInto(action);
  }

  /**
   * Asynchronously fulfills the request into the specified {@link ImageView}.
   * <p>
   * <em>Note:</em> This method keeps a weak reference to the {@link ImageView} instance and will
   * automatically support object recycling.
   */
  public void into(ImageView target) {
    into(target, null);
  }

  /**
   * Asynchronously fulfills the request into the specified {@link ImageView} and invokes the
   * target {@link Callback} if it's not {@code null}.
   * <p>
   * <em>Note:</em> The {@link Callback} param is a strong reference and will prevent your
   * {@link android.app.Activity} or {@link android.app.Fragment} from being garbage collected. If
   * you use this method, it is <b>strongly</b> recommended you invoke an adjacent
   * {@link Picasso#cancelRequest(android.widget.ImageView)} call to prevent temporary leaking.
   */
  public void into(ImageView target, Callback callback) {
    long started = System.nanoTime();
    checkMain();

    if (target == null) {
      throw new IllegalArgumentException("Target must not be null.");
    }

    if (!data.hasImage()) {
      picasso.cancelRequest(target);
      if (setPlaceholder) {
        setPlaceholder(target, getPlaceholderDrawable());
      }
      return;
    }

    if (deferred) {
      if (data.hasSize()) {
        throw new IllegalStateException("Fit cannot be used with resize.");
      }
      int width = target.getWidth();
      int height = target.getHeight();
      if (width == 0 || height == 0) {
        if (setPlaceholder) {
          setPlaceholder(target, getPlaceholderDrawable());
        }
        picasso.defer(target, new DeferredRequestCreator(this, target, callback));
        return;
      }
      data.resize(width, height);
    }

    Request request = createRequest(started);
    String requestKey = createKey(request);

    if (!skipMemoryCache) {
      Bitmap bitmap = picasso.quickMemoryCacheCheck(requestKey);
      if (bitmap != null) {
        picasso.cancelRequest(target);
        setBitmap(target, picasso.context, bitmap, MEMORY, noFade, picasso.indicatorsEnabled);
        if (picasso.loggingEnabled) {
          log(OWNER_MAIN, VERB_COMPLETED, request.plainId(), "from " + MEMORY);
        }
        if (callback != null) {
          callback.onSuccess();
        }
        return;
      }
    }

    if (setPlaceholder) {
      setPlaceholder(target, getPlaceholderDrawable());
    }

    Action action =
        new ImageViewAction(picasso, target, request, skipMemoryCache, noFade, errorResId,
            errorDrawable, requestKey, tag, callback);

    picasso.enqueueAndSubmit(action);
  }

  private Drawable getPlaceholderDrawable() {
    if (placeholderResId != 0) {
      return picasso.context.getResources().getDrawable(placeholderResId);
    } else {
      return placeholderDrawable; // This may be null which is expected and desired behavior.
    }
  }

  /** Create the request optionally passing it through the request transformer. */
  /** 创建一个request，并将其传递给transformer。 */
  private Request createRequest(long started) {
    int id = getRequestId();

    Request request = data.build();
    request.id = id;
    request.started = started;

    boolean loggingEnabled = picasso.loggingEnabled;
    if (loggingEnabled) {
      log(OWNER_MAIN, VERB_CREATED, request.plainId(), request.toString());
    }

    Request transformed = picasso.transformRequest(request);
    if (transformed != request) {
      // If the request was changed, copy over the id and timestamp from the original.
      transformed.id = id;
      transformed.started = started;

      if (loggingEnabled) {
        log(OWNER_MAIN, VERB_CHANGED, transformed.logId(), "into " + transformed);
      }
    }

    return transformed;
  }

  private void performRemoteViewInto(RemoteViewsAction action) {
    if (!skipMemoryCache) {
      Bitmap bitmap = picasso.quickMemoryCacheCheck(action.getKey());
      if (bitmap != null) {
        action.complete(bitmap, MEMORY);
        return;
      }
    }

    if (placeholderResId != 0) {
      action.setImageResource(placeholderResId);
    }

    picasso.enqueueAndSubmit(action);
  }
}
