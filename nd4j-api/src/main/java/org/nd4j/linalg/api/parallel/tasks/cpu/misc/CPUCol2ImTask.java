package org.nd4j.linalg.api.parallel.tasks.cpu.misc;

import io.netty.buffer.ByteBuf;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.parallel.tasks.CPUTaskExecutor;
import org.nd4j.linalg.api.parallel.tasks.Task;
import org.nd4j.linalg.api.parallel.tasks.TaskExecutorProvider;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel Col2Im implementation
 *
 * @author Alex Black
 */
public class CPUCol2ImTask implements Task<INDArray>, Future<INDArray> {
    protected final AtomicInteger splitCount = new AtomicInteger(0);
    protected final int maxSplits;
    protected final CountDownLatch latch;
    protected volatile boolean cancelled = false;

    protected final INDArray col;
    protected INDArray imgOut;
    protected final int kernelHeight;
    protected final int kernelWidth;
    protected final int strideY;
    protected final int strideX;
    protected final int padHeight;
    protected final int padWidth;
    protected final int imgHeight;
    protected final int imgWidth;
    protected final int parallelThreshold;

    protected final int depth;

    public CPUCol2ImTask(INDArray col, int strideY, int strideX, int padHeight, int padWidth, int imgHeight, int imgWidth, int parallelThreshold) {
        this(col, getNewOutputArray(col, imgHeight, imgWidth),
                strideY, strideX, padHeight, padWidth,
                imgHeight, imgWidth,
                0, col.size(0), //example ranges
                0, col.size(1), //depth ranges
                parallelThreshold);
        //NOTE: Ranges above are [from,to) i.e., exclusive of to, inclusive of from
    }

    public CPUCol2ImTask(INDArray col, INDArray imgOut, int strideY, int strideX, int padHeight, int padWidth, int imgHeight, int imgWidth,
                         int exampleFrom, int exampleTo, int depthFrom, int depthTo, int parallelThreshold) {
        this.col = col;
        this.imgOut = imgOut;
        this.kernelHeight = col.size(2);
        this.kernelWidth = col.size(3);
        this.strideY = strideY;
        this.strideX = strideX;
        this.padHeight = padHeight;
        this.padWidth = padWidth;
        this.imgHeight = imgHeight;
        this.imgWidth = imgWidth;
        this.parallelThreshold = parallelThreshold;

        this.depth = col.size(1);
        this.maxSplits = col.size(0) * depth; //examples * depth
        this.latch = new CountDownLatch(maxSplits);

    }


    private static INDArray getNewOutputArray(INDArray col, int imgHeight, int imgWidth) {
        //number of images
        int n = col.size(0);
        //number of columns
        int c = col.size(1);

        return Nd4j.create(n, c, imgHeight, imgWidth);
    }

    private int[] mapSplitIndexToExampleAndDepth(int splitIdx) {
        //TODO: consider strides etc, false sharing etc when doing this
        int[] arr = new int[2];
        arr[0] = splitIdx / depth;
        arr[1] = splitIdx % depth;
        return arr;
    }

    @Override
    public INDArray call() {
        DataBuffer dbIn = col.data();

        int thisIdx = splitCount.getAndIncrement();
        while (thisIdx < maxSplits) {
            int[] arr = mapSplitIndexToExampleAndDepth(thisIdx);
            int e = arr[0];
            int d = arr[1];

            if (dbIn.allocationMode() == DataBuffer.AllocationMode.HEAP) {
                if (dbIn.dataType() == DataBuffer.Type.FLOAT) {
                    doHeapFloat(e, d);
                } else {
                    doHeapDouble(e, d);
                }
            } else {
                if (dbIn.dataType() == DataBuffer.Type.FLOAT) {
                    doDirectFloat(e, d);
                } else {
                    doDirectDouble(e, d);
                }
            }

            latch.countDown();
            thisIdx = splitCount.getAndIncrement();
        }

        return null;
    }

    private void doHeapFloat(int ex, int d) {
        DataBuffer dbCol = col.data();
        DataBuffer dbOut = imgOut.data();

        int outArrayOffset = imgOut.offset();
        int[] outShape = imgOut.shape();
        int[] outStride = imgOut.stride();

        int inOffset = col.offset();
        int[] inShape = col.shape();
        int[] inStride = col.stride();

        int[] outIndices = new int[4];
        int[] inIndices = new int[6];

        final int inStride2 = inStride[2];
        final int inStride3 = inStride[3];
        final int outStride2 = outStride[2];
        final int outStride3 = outStride[3];
        final int outShape2 = outShape[2];
        final int outShape3 = outShape[3];

        final int yOutTo = inShape[4];
        final int xOutTo = inShape[5];


        final boolean padding = padHeight > 0 || padWidth > 0;

        float[] fIn = (float[]) dbCol.array();
        float[] fOut = (float[]) dbOut.array();

        inIndices[0] = ex;
        inIndices[1] = d;
        outIndices[0] = ex;
        outIndices[1] = d;

        for (int x = 0; x < xOutTo; x++) {  //Patch number along width
            for (int y = 0; y < yOutTo; y++) {  //Patch number along height
                inIndices[4] = y;   //patch number (along height)
                inIndices[5] = x;   //patch number (along width)
                int baseOffsetIn = getOffsetUnsafe6(inOffset, inShape, inStride, inIndices);

                if (padding) {
                    int i = y * strideY - padHeight;    //index along height of first element of patch in original img
                    int j = x * strideX - padWidth;     //index along width of first element in patch in original img
                    outIndices[2] = i;  //along height
                    outIndices[3] = j;  //along width

                    int baseOffsetOut = getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2 <= inStride3) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            if (j + patchX < 0 || j + patchX >= outShape3) continue;

                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                if (i + patchY < 0 || i + patchY >= outShape2) continue;
                                fOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        fIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            if (i + patchY < 0 || i + patchY >= outShape2) continue;
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                if (j + patchX < 0 || j + patchX >= outShape3) continue;
                                fOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        fIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    }
                } else {
                    //No padding
                    int i = y * strideY;    //index along height of first element of patch in output img
                    int j = x * strideX;     //index along width of first element in patch in output img

                    outIndices[2] = i;
                    outIndices[3] = j;

                    int baseOffsetOut = getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2 <= inStride3) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                fOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        fIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                fOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        fIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    }
                }
            }
        }
    }

    private void doHeapDouble(int ex, int d) {
        DataBuffer dbCol = col.data();
        DataBuffer dbOut = imgOut.data();

        int outArrayOffset = imgOut.offset();
        int[] outShape = imgOut.shape();
        int[] outStride = imgOut.stride();

        int inOffset = col.offset();
        int[] inShape = col.shape();
        int[] inStride = col.stride();

        int[] outIndices = new int[4];
        int[] inIndices = new int[6];

        final int inStride2 = inStride[2];
        final int inStride3 = inStride[3];
        final int outStride2 = outStride[2];
        final int outStride3 = outStride[3];
        final int outShape2 = outShape[2];
        final int outShape3 = outShape[3];

        final int yOutTo = inShape[4];
        final int xOutTo = inShape[5];


        final boolean padding = padHeight > 0 || padWidth > 0;

        double[] dIn = (double[]) dbCol.array();
        double[] dOut = (double[]) dbOut.array();

        inIndices[0] = ex;
        inIndices[1] = d;
        outIndices[0] = ex;
        outIndices[1] = d;

        for (int x = 0; x < xOutTo; x++) {  //Patch number along width
            for (int y = 0; y < yOutTo; y++) {  //Patch number along height
                inIndices[4] = y;   //patch number (along height)
                inIndices[5] = x;   //patch number (along width)
                int baseOffsetIn = getOffsetUnsafe6(inOffset, inShape, inStride, inIndices);

                if (padding) {
                    int i = y * strideY - padHeight;    //index along height of first element of patch in original img
                    int j = x * strideX - padWidth;     //index along width of first element in patch in original img
                    outIndices[2] = i;  //along height
                    outIndices[3] = j;  //along width

                    int baseOffsetOut = getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2 <= inStride3) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            if (j + patchX < 0 || j + patchX >= outShape3) continue;
                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                if (i + patchY < 0 || i + patchY >= outShape2) continue;
                                dOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        dIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            if (i + patchY < 0 || i + patchY >= outShape2) continue;
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                if (j + patchX < 0 || j + patchX >= outShape3) continue;
                                dOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        dIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    }
                } else {
                    //No padding
                    int i = y * strideY;    //index along height of first element of patch in output img
                    int j = x * strideX;     //index along width of first element in patch in output img

                    outIndices[2] = i;
                    outIndices[3] = j;

                    int baseOffsetOut = getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2 <= inStride3) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                dOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        dIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                dOut[baseOffsetOut + patchY * outStride2 + patchX * outStride3] +=
                                        dIn[baseOffsetIn + patchY * inStride2 + patchX * inStride3];
                            }
                        }
                    }
                }
            }
        }
    }

    private void doDirectFloat(int ex, int d) {
        DataBuffer dbCol = col.data();
        DataBuffer dbOut = imgOut.data();

        int outArrayOffset = imgOut.offset();
        int[] outShape = imgOut.shape();
        int[] outStride = imgOut.stride();

        int inOffset = col.offset();
        int[] inShape = col.shape();
        int[] inStride = col.stride();

        int[] outIndices = new int[4];
        int[] inIndices = new int[6];

        final int inStride2_times4 = inStride[2] * 4;
        final int inStride3_times4 = inStride[3] * 4;
        final int outStride2_times4 = outStride[2] * 4;
        final int outStride3_times4 = outStride[3] * 4;
        final int outShape2 = outShape[2];
        final int outShape3 = outShape[3];

        final int yOutTo = inShape[4];
        final int xOutTo = inShape[5];


        final boolean padding = padHeight > 0 || padWidth > 0;

        ByteBuf nbbIn = dbCol.asNetty();
        ByteBuf nbbOut = dbOut.asNetty();

        inIndices[0] = ex;
        inIndices[1] = d;
        outIndices[0] = ex;
        outIndices[1] = d;

        for (int x = 0; x < xOutTo; x++) {  //Patch number along width
            for (int y = 0; y < yOutTo; y++) {  //Patch number along height
                inIndices[4] = y;   //patch number (along height)
                inIndices[5] = x;   //patch number (along width)
                int baseOffsetInBytes = 4 * getOffsetUnsafe6(inOffset, inShape, inStride, inIndices);

                if (padding) {
                    int i = y * strideY - padHeight;    //index along height of first element of patch in original img
                    int j = x * strideX - padWidth;     //index along width of first element in patch in original img
                    outIndices[2] = i;  //along height
                    outIndices[3] = j;  //along width

                    int baseOffsetOutBytes = 4 * getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2_times4 <= inStride3_times4) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            if (j + patchX < 0 || j + patchX >= outShape3) continue;
                            int outBufferIdxXBytes = baseOffsetOutBytes + patchX * outStride3_times4;
                            int inBufferIdxXBytes = baseOffsetInBytes + patchX * inStride3_times4;

                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                if (i + patchY < 0 || i + patchY >= outShape2) continue;
                                int byteOffset = outBufferIdxXBytes + patchY * outStride2_times4;
                                nbbOut.setFloat(byteOffset, nbbOut.getFloat(byteOffset) +
                                        nbbIn.getFloat(inBufferIdxXBytes + patchY * inStride2_times4));
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            if (i + patchY < 0 || i + patchY >= outShape2) continue;
                            int outBufferIdxYBytes = baseOffsetOutBytes + patchY * outStride2_times4;
                            int inBufferIdxYBytes = baseOffsetInBytes + patchY * inStride2_times4;
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                if (j + patchX < 0 || j + patchX >= outShape3) continue;
                                int byteOffset = outBufferIdxYBytes + patchX * outStride3_times4;
                                nbbOut.setFloat(byteOffset, nbbOut.getFloat(byteOffset) +
                                        nbbIn.getFloat(inBufferIdxYBytes + patchX * inStride3_times4));
                            }
                        }
                    }
                } else {
                    //No padding
                    int i = y * strideY;    //index along height of first element of patch in output img
                    int j = x * strideX;     //index along width of first element in patch in output img

                    outIndices[2] = i;
                    outIndices[3] = j;

                    int baseOffsetOutBytes = 4 * getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2_times4 <= inStride3_times4) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            int outBufferIdxXBytes = baseOffsetOutBytes + patchX * outStride3_times4;
                            int inBufferIdxXBytes = baseOffsetInBytes + patchX * inStride3_times4;
                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                int byteOffset = outBufferIdxXBytes + patchY * outStride2_times4;
                                nbbOut.setFloat(byteOffset, nbbOut.getFloat(byteOffset) +
                                        nbbIn.getFloat(inBufferIdxXBytes + patchY * inStride2_times4));
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            int outBufferIdxYBytes = baseOffsetOutBytes + patchY * outStride2_times4;
                            int inBufferIdxYBytes = baseOffsetInBytes + patchY * inStride2_times4;
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                int byteOffset = outBufferIdxYBytes + patchX * outStride3_times4;
                                nbbOut.setFloat(byteOffset, nbbOut.getFloat(byteOffset) +
                                        nbbIn.getFloat(inBufferIdxYBytes + patchX * inStride3_times4));
                            }
                        }
                    }
                }
            }
        }
    }

    private void doDirectDouble(int ex, int d) {
        DataBuffer dbCol = col.data();
        DataBuffer dbOut = imgOut.data();

        int outArrayOffset = imgOut.offset();
        int[] outShape = imgOut.shape();
        int[] outStride = imgOut.stride();

        int inOffset = col.offset();
        int[] inShape = col.shape();
        int[] inStride = col.stride();

        int[] outIndices = new int[4];
        int[] inIndices = new int[6];

        final int inStride2_times8 = inStride[2] * 8;
        final int inStride3_times8 = inStride[3] * 8;
        final int outStride2_times8 = outStride[2] * 8;
        final int outStride3_times8 = outStride[3] * 8;
        final int outShape2 = outShape[2];
        final int outShape3 = outShape[3];

        final int yOutTo = inShape[4];
        final int xOutTo = inShape[5];


        final boolean padding = padHeight > 0 || padWidth > 0;

        ByteBuf nbbIn = dbCol.asNetty();
        ByteBuf nbbOut = dbOut.asNetty();

        inIndices[0] = ex;
        inIndices[1] = d;
        outIndices[0] = ex;
        outIndices[1] = d;

        for (int x = 0; x < xOutTo; x++) {  //Patch number along width
            for (int y = 0; y < yOutTo; y++) {  //Patch number along height
                inIndices[4] = y;   //patch number (along height)
                inIndices[5] = x;   //patch number (along width)
                int baseOffsetInBytes = 8 * getOffsetUnsafe6(inOffset, inShape, inStride, inIndices);

                if (padding) {
                    int i = y * strideY - padHeight;    //index along height of first element of patch in original img
                    int j = x * strideX - padWidth;     //index along width of first element in patch in original img
                    outIndices[2] = i;  //along height
                    outIndices[3] = j;  //along width

                    int baseOffsetOutBytes = 8 * getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2_times8 <= inStride3_times8) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            if (j + patchX < 0 || j + patchX >= outShape3) continue;
                            int outBufferIdxXBytes = baseOffsetOutBytes + patchX * outStride3_times8;
                            int inBufferIdxXBytes = baseOffsetInBytes + patchX * inStride3_times8;

                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                if (i + patchY < 0 || i + patchY >= outShape2) continue;
                                int byteOffset = outBufferIdxXBytes + patchY * outStride2_times8;
                                nbbOut.setDouble(byteOffset, nbbOut.getDouble(byteOffset) +
                                        nbbIn.getDouble(inBufferIdxXBytes + patchY * inStride2_times8));
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            if (i + patchY < 0 || i + patchY >= outShape2) continue;
                            int outBufferIdxYBytes = baseOffsetOutBytes + patchY * outStride2_times8;
                            int inBufferIdxYBytes = baseOffsetInBytes + patchY * inStride2_times8;
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                if (j + patchX < 0 || j + patchX >= outShape3) continue;
                                int byteOffset = outBufferIdxYBytes + patchX * outStride3_times8;
                                nbbOut.setDouble(byteOffset, nbbOut.getDouble(byteOffset) +
                                        nbbIn.getDouble(inBufferIdxYBytes + patchX * inStride3_times8));
                            }
                        }
                    }
                } else {
                    //No padding
                    int i = y * strideY;    //index along height of first element of patch in output img
                    int j = x * strideX;     //index along width of first element in patch in output img

                    outIndices[2] = i;
                    outIndices[3] = j;

                    int baseOffsetOutBytes = 8 * getOffsetUnsafe4(outArrayOffset, outShape, outStride, outIndices);

                    if (inStride2_times8 <= inStride3_times8) {
                        //Want dimension 2 (along height) in inner loop for cache efficiency
                        for (int patchX = 0; patchX < kernelWidth; patchX++) {
                            int outBufferIdxXBytes = baseOffsetOutBytes + patchX * outStride3_times8;
                            int inBufferIdxXBytes = baseOffsetInBytes + patchX * inStride3_times8;
                            for (int patchY = 0; patchY < kernelHeight; patchY++) {
                                int byteOffset = outBufferIdxXBytes + patchY * outStride2_times8;
                                nbbOut.setDouble(byteOffset, nbbOut.getDouble(byteOffset) +
                                        nbbIn.getDouble(inBufferIdxXBytes + patchY * inStride2_times8));
                            }
                        }
                    } else {
                        //Want dimension 3 (along width) in inner loop for cache efficiency
                        for (int patchY = 0; patchY < kernelHeight; patchY++) {
                            int outBufferIdxYBytes = baseOffsetOutBytes + patchY * outStride2_times8;
                            int inBufferIdxYBytes = baseOffsetInBytes + patchY * inStride2_times8;
                            for (int patchX = 0; patchX < kernelWidth; patchX++) {
                                int byteOffset = outBufferIdxYBytes + patchX * outStride3_times8;
                                nbbOut.setDouble(byteOffset, nbbOut.getDouble(byteOffset) +
                                        nbbIn.getDouble(inBufferIdxYBytes + patchX * inStride3_times8));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Calculate buffer offset (like Shape.getOffset) without checking on input for negative indices etc
     * normally negative indices are bad, OK here because of other checks on input indices
     * Uses unrolled loop specifically for length 4
     */
    private static int getOffsetUnsafe4(int baseOffset, int[] shape, int[] stride, int[] indices) {
        int offset = baseOffset;
        if (shape[0] != 1) offset += indices[0] * stride[0];
        if (shape[1] != 1) offset += indices[1] * stride[1];
        if (shape[2] != 1) offset += indices[2] * stride[2];
        if (shape[3] != 1) offset += indices[3] * stride[3];
        return offset;
    }

    /**
     * A version of Shape.getOffset without checking on input for negative indices etc
     * normally negative indices are bad, OK here because of other checks on input indices
     * Uses unrolled loop specifically for length 6, where indices[2] and indices[3] are zero (always are here)
     */
    private static int getOffsetUnsafe6(int baseOffset, int[] shape, int[] stride, int[] indices) {
        int offset = baseOffset;
        if (shape[0] != 1) offset += indices[0] * stride[0];
        if (shape[1] != 1) offset += indices[1] * stride[1];
        if (shape[4] != 1) offset += indices[4] * stride[4];
        if (shape[5] != 1) offset += indices[5] * stride[5];
        return offset;
    }

    @Override
    public INDArray invokeBlocking() {
        invokeAsync();
        return blockUntilComplete();
    }

    @Override
    public void invokeAsync() {
        CPUTaskExecutor.getInstance().executeAsync(this);
    }

    @Override
    public INDArray blockUntilComplete() {
        try {
            return this.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if(!isDone() && !cancelled){
            splitCount.addAndGet(maxSplits);
            while(latch.getCount() > 0) latch.countDown();
            cancelled = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isDone() {
        return latch.getCount() <= 0;
    }

    @Override
    public INDArray get() throws InterruptedException, ExecutionException {
        latch.await();
        return imgOut;
    }

    @Override
    public INDArray get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        latch.await(timeout, unit);
        return imgOut;
    }
}
