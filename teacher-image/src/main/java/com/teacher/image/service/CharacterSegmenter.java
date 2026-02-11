package com.teacher.image.service;

import com.teacher.common.dto.CalligraphyImage;
import com.teacher.common.exception.ImageProcessingException;
import com.teacher.common.util.ImageUtils;
import com.teacher.image.config.OpenCvProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 字符切分服务：利用轮廓检测将整页图片切分成独立的单字图片。
 * <p>
 * 针对格子练字纸（如正心格、田字格、米字格）做了专门优化：
 * 1. 先去除水平和垂直的网格线
 * 2. 轻量膨胀连接同一个字的笔画碎片
 * 3. 按面积、宽高比、尺寸一致性多维过滤
 * 4. 按阅读顺序排序输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterSegmenter {

    private final OpenCvProperties properties;
    private final ImagePreprocessor preprocessor;

    /**
     * 对整页图片进行切分，返回按阅读顺序排列的单字图片列表。
     * <p>
     * 自动适配不同尺寸的输入：
     * - 小图（<300px）：视为单字图，跳过切分直接返回
     * - 大图：完整切分流水线（去网格线 -> 膨胀 -> 轮廓检测 -> 多维过滤）
     */
    public List<CalligraphyImage.SingleCharImage> segment(byte[] originalBytes) {
        Mat src = preprocessor.readImage(originalBytes);
        int imgW = src.cols();
        int imgH = src.rows();

        // ==================== 小图快速通道 ====================
        // 宽和高都小于阈值 → 几乎肯定是单字截图，直接整图返回
        int threshold = properties.getSmallImageThreshold();
        if (imgW < threshold && imgH < threshold) {
            log.info("小图模式 ({}x{} < {}px)，直接作为单字返回", imgW, imgH, threshold);
            byte[] charBytes = preprocessor.matToBytes(src);
            return List.of(CalligraphyImage.SingleCharImage.builder()
                    .index(0)
                    .base64(ImageUtils.toBase64(charBytes))
                    .x(0).y(0).width(imgW).height(imgH)
                    .build());
        }

        // ==================== 大图完整流水线 ====================
        Mat binary = preprocessor.preprocess(src);

        // === 第1步：去除网格线 ===
        Mat cleaned = removeGridLines(binary, imgW, imgH);

        // === 第2步：轻量膨胀，连接同一个字的笔画碎片 ===
        Mat dilated = new Mat();
        if (properties.getDilateIterations() > 0) {
            Mat kernel = getStructuringElement(MORPH_RECT, new Size(2, 2));
            dilate(cleaned, dilated, kernel, new Point(-1, -1),
                    properties.getDilateIterations(), BORDER_CONSTANT, null);
        } else {
            dilated = cleaned;
        }

        // === 第3步：寻找外部轮廓 ===
        MatVector contours = new MatVector();
        findContours(dilated, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
        log.info("检测到 {} 个初始轮廓", contours.size());

        // === 第4步：动态计算过滤阈值 + 多维过滤 ===
        double totalArea = (double) imgW * imgH;
        // 取配置的固定值和动态比例值中的较小者，适配不同分辨率
        double dynamicMinArea = totalArea * properties.getMinContourAreaRatio();
        double effectiveMinArea = Math.min(properties.getMinContourArea(), dynamicMinArea);
        double maxArea = totalArea * properties.getMaxContourAreaRatio();

        log.info("过滤阈值: minArea={}, maxArea={}, 图片总面积={}", 
                (int) effectiveMinArea, (int) maxArea, (int) totalArea);

        List<Rect> boundingRects = new ArrayList<>();
        for (long i = 0; i < contours.size(); i++) {
            Rect rect = boundingRect(contours.get(i));
            double area = (double) rect.width() * rect.height();

            // 过滤太小的噪点
            if (area < effectiveMinArea) continue;

            // 过滤太大的（整页边框、大块网格残留）
            if (area > maxArea) continue;

            // 过滤细长条（残留的网格线片段）
            double aspect = (double) Math.max(rect.width(), rect.height())
                    / Math.min(rect.width(), rect.height());
            if (aspect > properties.getMaxAspectRatio()) continue;

            boundingRects.add(rect);
        }
        log.info("多维过滤后有效轮廓: {} 个", boundingRects.size());

        if (boundingRects.isEmpty()) {
            // 兜底：如果完整流水线也没检测到字，把整图当一个字返回（而不是报错）
            log.warn("未检测到独立字符轮廓，将整图作为单字返回");
            byte[] charBytes = preprocessor.matToBytes(src);
            return List.of(CalligraphyImage.SingleCharImage.builder()
                    .index(0)
                    .base64(ImageUtils.toBase64(charBytes))
                    .x(0).y(0).width(imgW).height(imgH)
                    .build());
        }

        // === 第5步：合并笔画碎片（仅合并非常接近的小块） ===
        List<Rect> merged = mergeCloseRects(boundingRects);
        log.info("合并后字符数: {}", merged.size());

        // === 第6步：尺寸一致性过滤（去掉异常大/小的杂块） ===
        List<Rect> filtered = filterByMedianSize(merged);
        log.info("尺寸一致性过滤后: {} 个字", filtered.size());

        // === 第7步：按阅读顺序排序 ===
        List<Rect> sorted = sortByReadingOrder(filtered);

        // === 第8步：切分并编码 ===
        int padding = properties.getCharPadding();
        List<CalligraphyImage.SingleCharImage> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Rect rect = sorted.get(i);
            int x = Math.max(0, rect.x() - padding);
            int y = Math.max(0, rect.y() - padding);
            int w = Math.min(imgW - x, rect.width() + 2 * padding);
            int h = Math.min(imgH - y, rect.height() + 2 * padding);

            Mat charMat = new Mat(src, new Rect(x, y, w, h));
            byte[] charBytes = preprocessor.matToBytes(charMat);

            result.add(CalligraphyImage.SingleCharImage.builder()
                    .index(i)
                    .base64(ImageUtils.toBase64(charBytes))
                    .x(x).y(y).width(w).height(h)
                    .build());
        }

        return result;
    }

    // ============================ 网格线去除 ============================

    /**
     * 去除练字纸上的水平线和垂直线。
     * 原理：用长条形态学核检测直线 -> 从二值图中减去。
     */
    private Mat removeGridLines(Mat binary, int imgWidth, int imgHeight) {
        if (!properties.isGridRemovalEnabled()) {
            return binary;
        }

        Mat cleaned = binary.clone();

        // 检测并去除水平线
        int hLineLen = (int) (imgWidth * properties.getGridLineMinRatio());
        Mat hKernel = getStructuringElement(MORPH_RECT, new Size(hLineLen, 1));
        Mat hLines = new Mat();
        morphologyEx(binary, hLines, MORPH_OPEN, hKernel);
        // 稍微膨胀水平线，确保完全擦除
        Mat hDilateKernel = getStructuringElement(MORPH_RECT, new Size(1, 3));
        dilate(hLines, hLines, hDilateKernel);
        subtract(cleaned, hLines, cleaned);

        // 检测并去除垂直线
        int vLineLen = (int) (imgHeight * properties.getGridLineMinRatio());
        Mat vKernel = getStructuringElement(MORPH_RECT, new Size(1, vLineLen));
        Mat vLines = new Mat();
        morphologyEx(binary, vLines, MORPH_OPEN, vKernel);
        Mat vDilateKernel = getStructuringElement(MORPH_RECT, new Size(3, 1));
        dilate(vLines, vLines, vDilateKernel);
        subtract(cleaned, vLines, cleaned);

        log.info("网格线去除完成 (水平核={}, 垂直核={})", hLineLen, vLineLen);
        return cleaned;
    }

    // ============================ 合并碎片 ============================

    /**
     * 合并距离非常近的矩形（同一个字被打散的笔画碎片）。
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

    /**
     * 判断两个矩形是否应该合并。
     * 只合并有重叠或间距小于 gap 的矩形。
     */
    private boolean shouldMerge(Rect a, Rect b, int gap) {
        // 水平间距
        int hGap = Math.max(0, Math.max(a.x(), b.x())
                - Math.min(a.x() + a.width(), b.x() + b.width()));
        // 垂直间距
        int vGap = Math.max(0, Math.max(a.y(), b.y())
                - Math.min(a.y() + a.height(), b.y() + b.height()));

        return hGap <= gap && vGap <= gap;
    }

    // ============================ 尺寸一致性过滤 ============================

    /**
     * 根据中位数尺寸过滤异常大/小的矩形。
     * 字帖上的字大小应该比较一致，偏离中位数太多的是杂块。
     */
    private List<Rect> filterByMedianSize(List<Rect> rects) {
        if (rects.size() <= 3) return rects;

        // 计算所有矩形面积的中位数
        List<Double> areas = rects.stream()
                .map(r -> (double) r.width() * r.height())
                .sorted()
                .toList();
        double median = areas.get(areas.size() / 2);

        // 保留面积在中位数 0.15 ~ 4.0 倍范围内的
        double minArea = median * 0.15;
        double maxArea = median * 4.0;

        List<Rect> filtered = rects.stream()
                .filter(r -> {
                    double area = (double) r.width() * r.height();
                    return area >= minArea && area <= maxArea;
                })
                .toList();

        if (filtered.isEmpty()) {
            log.warn("尺寸一致性过滤后为空，回退到过滤前结果");
            return rects;
        }
        return new ArrayList<>(filtered);
    }

    // ============================ 阅读顺序排序 ============================

    /**
     * 按阅读顺序排序：先根据 Y 坐标分行，然后每行内按 X 坐标排序。
     */
    private List<Rect> sortByReadingOrder(List<Rect> rects) {
        if (rects.size() <= 1) return rects;

        rects.sort(Comparator.comparingInt(Rect::y));

        // 计算平均高度，用于行分组
        int avgHeight = rects.stream().mapToInt(Rect::height).sum() / rects.size();

        List<List<Rect>> rows = new ArrayList<>();
        List<Rect> currentRow = new ArrayList<>();
        currentRow.add(rects.get(0));

        for (int i = 1; i < rects.size(); i++) {
            Rect curr = rects.get(i);
            // 判断是否新行：当前矩形的 Y 与当前行第一个矩形的 Y 相差超过平均高度的 60%
            Rect rowFirst = currentRow.get(0);
            if (curr.y() - rowFirst.y() > avgHeight * 0.6) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
            currentRow.add(curr);
        }
        rows.add(currentRow);

        // 每行内按 X 排序
        List<Rect> sorted = new ArrayList<>();
        for (List<Rect> row : rows) {
            row.sort(Comparator.comparingInt(Rect::x));
            sorted.addAll(row);
        }

        log.info("排序完成: {} 行, 总计 {} 个字", rows.size(), sorted.size());
        return sorted;
    }
}
