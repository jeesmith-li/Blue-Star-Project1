package com.example.meituan.util;

import com.example.meituan.config.AppConfig;
import com.example.meituan.ocr.OcrClient;
import com.example.meituan.ocr.OcrResult;
import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 3.1 筛选诊断工具
 * 分析为什么"未回复"没有被识别
 */
public class FilterDiagnosticTool {
    private static final Logger logger = LoggerFactory.getLogger(FilterDiagnosticTool.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: FilterDiagnosticTool <image_file>");
            System.exit(1);
        }

        String imagePath = args[0];

        try {
            AppConfig config = new AppConfig();
            OcrClient ocrClient = new OcrClient(config);

            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                System.err.println("Error: File not found: " + imagePath);
                System.exit(1);
            }

            BufferedImage image = javax.imageio.ImageIO.read(imageFile);

            System.out.println("=".repeat(60));
            System.out.println("PHASE 3.1 筛选诊断工具");
            System.out.println("Image: " + imagePath);
            System.out.println("Size: " + image.getWidth() + "x" + image.getHeight());
            System.out.println("=".repeat(60));

            // 运行 OCR
            OcrResult result = ocrClient.recognize(image);

            if (!result.isSuccess()) {
                System.err.println("OCR failed: " + result.getErrorMessage());
                System.exit(1);
            }

            System.out.println("\nTotal text boxes: " + result.getTextBoxCount());

            // 分析筛选相关关键词
            String[] keywords = {"未回复", "已回复", "全部", "差评", "好评", "中评", "评价",
                               "星", "1星", "2星", "3星", "4星", "5星", "未", "回复", "回", "复"};

            System.out.println("\n" + "=".repeat(60));
            System.out.println("筛选相关文本分析");
            System.out.println("=".repeat(60));

            List<OcrTextBox> filterRelated = new ArrayList<>();

            for (OcrTextBox box : result.getTextBoxes()) {
                String text = box.getText();
                for (String kw : keywords) {
                    if (text.contains(kw)) {
                        filterRelated.add(box);
                        break;
                    }
                }
            }

            System.out.println("\n找到 " + filterRelated.size() + " 个包含筛选关键词的文本框:");
            System.out.println();

            for (int i = 0; i < filterRelated.size(); i++) {
                OcrTextBox box = filterRelated.get(i);
                System.out.printf("%3d. Text: [%s]\n", i + 1, box.getText());
                System.out.printf("     Confidence: %.3f\n", box.getConfidence());
                System.out.printf("     BBox: (%d,%d) size=%dx%d\n",
                        box.getX(), box.getY(), box.getWidth(), box.getHeight());
                System.out.printf("     Center: (%d,%d)\n", box.getCenterX(), box.getCenterY());
                System.out.println();
            }

            // 精确查找"未回复"
            System.out.println("=".repeat(60));
            System.out.println("精确匹配分析");
            System.out.println("=".repeat(60));

            System.out.println("\n查找包含「未回复」的文本框:");
            long unrepliedCount = result.getTextBoxes().stream()
                    .filter(box -> box.getText().contains("未回复"))
                    .count();
            System.out.println("数量: " + unrepliedCount);

            if (unrepliedCount > 0) {
                System.out.println("\n详细信息:");
                result.getTextBoxes().stream()
                        .filter(box -> box.getText().contains("未回复"))
                        .forEach(box -> {
                            System.out.printf("  Text: [%s], Conf: %.3f, BBox: (%d,%d,%dx%d), Center: (%d,%d)\n",
                                    box.getText(), box.getConfidence(),
                                    box.getX(), box.getY(), box.getWidth(), box.getHeight(),
                                    box.getCenterX(), box.getCenterY());
                        });
            }

            // 查找可能被拆分的情况
            System.out.println("\n查找单字「未」:");
            result.getTextBoxes().stream()
                    .filter(box -> box.getText().equals("未"))
                    .forEach(box -> System.out.printf("  Found: (%d,%d)\n", box.getCenterX(), box.getCenterY()));

            System.out.println("\n查找单字或短语「回复」:");
            result.getTextBoxes().stream()
                    .filter(box -> box.getText().contains("回复"))
                    .forEach(box -> System.out.printf("  Text: [%s] at (%d,%d)\n",
                            box.getText(), box.getCenterX(), box.getCenterY()));

            // 空间关系分析
            System.out.println("\n" + "=".repeat(60));
            System.out.println("空间关系分析");
            System.out.println("=".repeat(60));

            if (!filterRelated.isEmpty()) {
                System.out.println("\n按 Y 坐标分组（可能的同一行）:");

                // 简单分组：Y 坐标相近的为同一行
                List<List<OcrTextBox>> rows = new ArrayList<>();
                int yTolerance = 30;

                for (OcrTextBox box : filterRelated) {
                    boolean addedToRow = false;
                    for (List<OcrTextBox> row : rows) {
                        if (Math.abs(row.get(0).getY() - box.getY()) < yTolerance) {
                            row.add(box);
                            addedToRow = true;
                            break;
                        }
                    }
                    if (!addedToRow) {
                        List<OcrTextBox> newRow = new ArrayList<>();
                        newRow.add(box);
                        rows.add(newRow);
                    }
                }

                for (int i = 0; i < rows.size(); i++) {
                    List<OcrTextBox> row = rows.get(i);
                    System.out.printf("\nRow %d (Y~%d):\n", i + 1, row.get(0).getY());
                    for (OcrTextBox box : row) {
                        System.out.printf("  [%s] at x=%d\n", box.getText(), box.getX());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error", e);
            e.printStackTrace();
        }
    }
}
