package com.teacher.image.service;

import com.teacher.common.exception.ImageProcessingException;
import com.teacher.image.config.OpenCvProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.*;
import org.springframework.stereotype.Service;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 图像预处理服务：灰度化、去噪、二值化、透视矫正。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagePreprocessor {

    private final OpenCvProperties properties;

    /**
     * 从字节数组读取图片为 OpenCV Mat。
     */
    public Mat readImage(byte[] imageBytes) {
        try {
            Mat raw = opencv_imgcodecs.imdecode(new Mat(imageBytes), opencv_imgcodecs.IMREAD_COLOR);
            if (raw.empty()) {
                throw new ImageProcessingException("无法解码图片，请确认图片格式正确");
            }
            log.info("图片读取成功: {}x{}", raw.cols(), raw.rows());
            return raw;
        } catch (ImageProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageProcessingException("读取图片失败", e);
        }
    }

    /**
     * 灰度化处理。
     */
    public Mat toGrayscale(Mat src) {
        Mat gray = new Mat();
        cvtColor(src, gray, COLOR_BGR2GRAY);
        return gray;
    }

    /**
     * 高斯模糊去噪。
     */
    public Mat denoise(Mat gray) {
        Mat blurred = new Mat();
        int kSize = properties.getGaussianKernelSize();
        GaussianBlur(gray, blurred, new Size(kSize, kSize), 0);
        return blurred;
    }

    /**
     * 自适应阈值二值化。
     */
    public Mat binarize(Mat gray) {
        Mat binary = new Mat();
        adaptiveThreshold(
                gray, binary, 255,
                ADAPTIVE_THRESH_GAUSSIAN_C,
                THRESH_BINARY_INV,
                properties.getAdaptiveBlockSize(),
                properties.getAdaptiveC()
        );
        return binary;
    }

    /**
     * 透视矫正：检测纸张边缘，将倾斜的图片拉直。
     */
    public Mat perspectiveCorrection(Mat src) {
        if (!properties.isPerspectiveCorrectionEnabled()) {
            log.debug("透视矫正已禁用，跳过");
            return src;
        }

        try {
            Mat gray = toGrayscale(src);
            Mat blurred = denoise(gray);
            Mat edges = new Mat();
            Canny(blurred, edges, 50, 150);

            // 膨胀边缘使其更连贯
            Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
            dilate(edges, edges, kernel);

            // 寻找轮廓
            MatVector contours = new MatVector();
            findContours(edges, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            if (contours.size() == 0) {
                log.warn("未检测到纸张边缘，跳过透视矫正");
                return src;
            }

            // 寻找最大轮廓
            Mat largest = null;
            double maxArea = 0;
            for (long i = 0; i < contours.size(); i++) {
                double area = contourArea(contours.get(i));
                if (area > maxArea) {
                    maxArea = area;
                    largest = contours.get(i);
                }
            }

            if (largest == null || maxArea < src.rows() * src.cols() * 0.2) {
                log.warn("最大轮廓面积过小 ({}), 跳过透视矫正", maxArea);
                return src;
            }

            // 近似多边形
            Mat approx = new Mat();
            double peri = arcLength(largest, true);
            approxPolyDP(largest, approx, 0.02 * peri, true);

            if (approx.rows() != 4) {
                log.warn("未检测到四边形纸张 (顶点数={}), 跳过透视矫正", approx.rows());
                return src;
            }

            // 获取四个顶点并排序
            Point2f srcPts = orderPoints(approx);
            int w = src.cols();
            int h = src.rows();

            float[] dstData = {0, 0, w, 0, w, h, 0, h};
            Point2f dstPts = new Point2f(4);
            dstPts.put(dstData);

            Mat transform = getPerspectiveTransform(srcPts, dstPts);
            Mat warped = new Mat();
            warpPerspective(src, warped, transform, new Size(w, h));

            log.info("透视矫正完成");
            return warped;

        } catch (Exception e) {
            log.warn("透视矫正失败，使用原图: {}", e.getMessage());
            return src;
        }
    }

    /**
     * 完整的预处理流水线：矫正 -> 灰度 -> 去噪 -> 二值化。
     */
    public Mat preprocess(Mat src) {
        Mat corrected = perspectiveCorrection(src);
        Mat gray = toGrayscale(corrected);
        Mat denoised = denoise(gray);
        return binarize(denoised);
    }

    /**
     * Mat 转字节数组（PNG 格式）。
     * 使用 JavaCPP 的 BytePointer 重载完成编码。
     */
    public byte[] matToBytes(Mat mat) {
        BytePointer buf = new BytePointer();
        try {
            boolean ok = opencv_imgcodecs.imencode(".png", mat, buf);
            if (!ok || buf.limit() == 0) {
                throw new ImageProcessingException("图片编码为 PNG 失败");
            }
            byte[] result = new byte[(int) buf.limit()];
            buf.get(result);
            return result;
        } finally {
            buf.deallocate();
        }
    }

    /**
     * 将近似多边形的 4 个顶点排序为：左上、右上、右下、左下。
     */
    private Point2f orderPoints(Mat approx) {
        // approxPolyDP 输出 CV_32SC2（整数二通道），通过 IntPointer 读取
        IntPointer ptr = new IntPointer(approx.data());
        float[][] pts = new float[4][2];
        for (int i = 0; i < 4; i++) {
            pts[i][0] = ptr.get(i * 2);      // x
            pts[i][1] = ptr.get(i * 2 + 1);  // y
        }

        // 按 sum(x+y) 和 diff(x-y) 排序确定四角位置
        float[] sums = new float[4];
        float[] diffs = new float[4];
        for (int i = 0; i < 4; i++) {
            sums[i] = pts[i][0] + pts[i][1];
            diffs[i] = pts[i][0] - pts[i][1];
        }

        int tl = argMin(sums);   // 左上：x+y 最小
        int br = argMax(sums);   // 右下：x+y 最大
        int tr = argMax(diffs);  // 右上：x-y 最大
        int bl = argMin(diffs);  // 左下：x-y 最小

        float[] ordered = {
                pts[tl][0], pts[tl][1],
                pts[tr][0], pts[tr][1],
                pts[br][0], pts[br][1],
                pts[bl][0], pts[bl][1]
        };

        Point2f result = new Point2f(4);
        result.put(ordered);
        return result;
    }

    private int argMin(float[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < arr[idx]) idx = i;
        }
        return idx;
    }

    private int argMax(float[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[idx]) idx = i;
        }
        return idx;
    }
}
