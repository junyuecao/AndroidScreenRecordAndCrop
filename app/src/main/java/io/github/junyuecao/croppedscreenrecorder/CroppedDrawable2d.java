package io.github.junyuecao.croppedscreenrecorder;

import android.util.Log;
import io.github.junyuecao.croppedscreenrecorder.gles.Drawable2d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;


/**
 * Tweaked version of Drawable2d that crop the texture coordinates.
 */
public class CroppedDrawable2d extends Drawable2d {
    private static final String TAG = "CroppedDrawable2d";

    private static final int SIZEOF_FLOAT = 4;

    private FloatBuffer mTweakedTexCoordArray;
    private float mTopCropped = 0.0f;
    private float mBottomCropped = 1.0f;
    private boolean mRecalculate;

    /**
     * Trivial constructor.
     */
    public CroppedDrawable2d(Prefab shape) {
        super(shape);
        mRecalculate = true;
    }

    public float getBottomCropped() {
        return mBottomCropped;
    }

    /**
     * @param bottomCropped defines the proportion to be cut on the top
     */
    public void setBottomCropped(float bottomCropped) {
        if (bottomCropped < 0.0f || bottomCropped > 1.0f) {
            throw new RuntimeException("invalid crop " + bottomCropped);
        }
        mBottomCropped = bottomCropped;
        mRecalculate = true;
    }

    /**
     * @param  crop defines the proportion to be cut on the top
     */
    public void setTopCropped(float crop) {
        if (crop < 0.0f || crop > 1.0f) {
            throw new RuntimeException("invalid crop " + crop);
        }
        mTopCropped = crop;
        mRecalculate = true;
    }

    /**
     * Returns the array of texture coordinates.  The first time this is called, we generate
     * a modified version of the array from the parent class.
     * <p>
     * To avoid allocations, this returns internal state.  The caller must not modify it.
     *
     * @see Drawable2d#FULL_RECTANGLE_TEX_COORDS
     */
    @Override
    public FloatBuffer getTexCoordArray() {
        if (mRecalculate) {
            //Log.v(TAG, "Scaling to " + mScale);
            FloatBuffer parentBuf = super.getTexCoordArray();
            int count = parentBuf.capacity();

            if (mTweakedTexCoordArray == null) {
                ByteBuffer bb = ByteBuffer.allocateDirect(count * SIZEOF_FLOAT);
                bb.order(ByteOrder.nativeOrder());
                mTweakedTexCoordArray = bb.asFloatBuffer();
            }

            // Texture coordinates range from 0.0 to 1.0, inclusive.  We do a simple scale
            // here, but we could get much fancier if we wanted to (say) zoom in and pan
            // around.
            FloatBuffer fb = mTweakedTexCoordArray;
            for (int i = 0; i < count; i++) {
                float fl = parentBuf.get(i);
                if (i == 0 || i == 4) {
                    fl = 0.0f;
                } else if (i == 2 || i == 6) {
                    fl = 1.0f;
                } else if (i == 1 || i == 3) {
                    // Crop the bottom
                    fl = mBottomCropped;
                } else if (i == 5 || i == 7) {
                    // Crop the top
                    fl = 1.0f - mTopCropped;
                }

                fb.put(i, fl);

                Log.d(TAG, "getTexCoordArray: " + fl);
            }

            mRecalculate = false;
        }

        return mTweakedTexCoordArray;
    }
}
