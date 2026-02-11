package com.teacher.image.service;

import com.teacher.common.dto.CalligraphyImage;
import com.teacher.common.exception.ImageProcessingException;
import com.teacher.common.util.ImageUtils;
import com.teacher.image.config.OpenCvProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 字符切分服务：利用轮廓检测将整页图片切分成独立的单字图片。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterSegmenter {

    private final OpenCvProperties properties;
    private final ImagePreprocessor preprocessor;

    /**
     * 对整页图片进行切分，返回按阅读顺序排列的单字图片列表。
     *
     * @param originalBytes 原始图片字节数组
     * @return 单字图片列表
     */
    public List<CalligraphyImage.SingleCharImage> segment(byte[] originalBytes) {
        Mat src = preprocessor.readImage(originalBytes);
        Mat binary = preprocessor.preprocess(src);

        // 膨胀处理，将相近的笔画连接起来形成完整的字
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
        Mat dilated = new Mat();
        dilate(binary, dilated, kernel, new Point(-1, -1),
                properties.getDilateIterations(), opencv_core.BORDER_CONSTANT, null);

        // 寻找外部轮廓
        MatVector contours = new MatVector();
        findContours(dilated, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

        log.info("检测到 {} 个初始轮廓", contours.size());

        // 提取有效的边界矩形
        List<Rect> boundingRects = new ArrayList<>();
        for (long i = 0; i < contours.size(); i++) {
            Rect rect = boundingRect(contours.get(i));
            double area = rect.width() * rect.height();
            if (area >= properties.getMinContourArea()) {
                boundingRects.add(rect);
            }
        }

        log.info("过滤后有效轮廓: {} 个", boundingRects.size());

        if (boundingRects.isEmpty()) {
            throw new ImageProcessingException("未在图片中检测到任何汉字，请确认图片内容");
        }

        // 合并过于接近的轮廓（可能是同一个字的不同笔画部分）
        List<Rect> merged = mergeCloseRects(boundingRects);
        log.info("合并后字符数: {}", merged.size());

        // 按阅读顺序排序：先按行（Y坐标分组），再按列（X坐标排序）
        List<Rect> sorted = sortByReadingOrder(merged);

        // 切分并编码为 Base64
        List<CalligraphyImage.SingleCharImage> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Rect rect = sorted.get(i);
            // 给每个字留一点边距
            int padding = 5;
            int x = Math.max(0, rect.x() - padding);
            int y = Math.max(0, rect.y() - padding);
            int w = Math.min(src.cols() - x, rect.width() + 2 * padding);
            int h = Math.min(src.rows() - y, rect.height() + 2 * padding);

            Mat charMat = new Mat(src, new Rect(x, y, w, h));
            byte[] charBytes = preprocessor.matToBytes(charMat);

            result.add(CalligraphyImage.SingleCharImage.builder()
                    .index(i)
                    .base64(ImageUtils.toBase64(charBytes))
                    .x(x)
                    .y(y)
                    .width(w)
                    .height(h)
                    .build());
        }

        return result;
    }

    /**
     * 合并相距过近的矩形区域。
     */
    private List<Rect> mergeCloseRects(List<Rect> rects) {
        List<Rect> result = new ArrayList<>(rects);
        boolean merged = true;
        int gap = properties.getMinCharGap();

        while (merged) {
            merged = false;
            for (int i = 0; i < result.size(); i++) {
                for (int j = i + 1; j < result.size(); j++) {
                    if (shouldMerge(result.get(i), result.get(j), gap)) {
                        Rect a = result.get(i);
                        Rect b = result.get(j);
                        int nx = Math.min(a.x(), b.x());
                        int ny = Math.min(a.y(), b.y());
                        int nx2 = Math.max(a.x() + a.width(), b.x() + b.width());
                        int ny2 = Math.max(a.y() + a.height(), b.y() + b.height());
                        result.set(i, new Rect(nx, ny, nx2 - nx, ny2 - ny));
                        result.remove(j);
                        merged = true;
                        break;
                    }
                }
                if (merged) break;
            }
        }
        return result;
    }

    private boolean shouldMerge(Rect a, Rect b, int gap) {
        return a.x() <= b.x() + b.width() + gap
                && b.x() <= a.x() + a.width() + gap
                && a.y() <= b.y() + b.height() + gap
                && b.y() <= a.y() + a.height() + gap;
    }

    /**
     * 按阅读顺序排序：先根据 Y 坐标分行，然后每行内按 X 坐标排序。
     */
    private List<Rect> sortByReadingOrder(List<Rect> rects) {
        if (rects.isEmpty()) return rects;

        // 先按 Y 坐标排序
        rects.sort(Comparator.comparingInt(Rect::y));

        // 按行分组（Y 坐标接近的归为同一行）
        List<List<Rect>> rows = new ArrayList<>();
        List<Rect> currentRow = new ArrayList<>();
        currentRow.add(rects.get(0));
        int avgHeight = rects.stream().mapToInt(Rect::height).sum() / rects.size();

        for (int i = 1; i < rects.size(); i++) {
            Rect prev = rects.get(i - 1);
            Rect curr = rects.get(i);
            // 如果 Y 坐标差距超过平均高度的一半，认为是新的一行
            if (curr.y() - prev.y() > avgHeight * 0.5) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
            currentRow.add(curr);
        }
        rows.add(currentRow);

        // 每行内按 X 坐标排序
        List<Rect> sorted = new ArrayList<>();
        for (List<Rect> row : rows) {
            row.sort(Comparator.comparingInt(Rect::x));
            sorted.addAll(row);
        }

        return sorted;
    }
}
