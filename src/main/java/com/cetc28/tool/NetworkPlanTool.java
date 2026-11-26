package com.cetc28.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络规划工具 — 距离计算、设备选型、子网规划、成本估算、方案验证
 */
@Component
public class NetworkPlanTool {

    private static final Map<String, double[]> CITY_COORDS = new LinkedHashMap<>();

    static {
        CITY_COORDS.put("北京", new double[]{39.9042, 116.4074});
        CITY_COORDS.put("上海", new double[]{31.2304, 121.4737});
        CITY_COORDS.put("广州", new double[]{23.1291, 113.2644});
        CITY_COORDS.put("深圳", new double[]{22.5431, 114.0579});
        CITY_COORDS.put("杭州", new double[]{30.2741, 120.1551});
        CITY_COORDS.put("成都", new double[]{30.5728, 104.0668});
        CITY_COORDS.put("武汉", new double[]{30.5928, 114.3055});
        CITY_COORDS.put("南京", new double[]{32.0603, 118.7969});
        CITY_COORDS.put("西安", new double[]{34.3416, 108.9398});
        CITY_COORDS.put("重庆", new double[]{29.4316, 106.9123});
        CITY_COORDS.put("天津", new double[]{39.3434, 117.3616});
        CITY_COORDS.put("苏州", new double[]{31.2990, 120.5853});
        CITY_COORDS.put("长沙", new double[]{28.2282, 112.9388});
        CITY_COORDS.put("郑州", new double[]{34.7473, 113.6249});
        CITY_COORDS.put("东莞", new double[]{23.0208, 113.7518});
        CITY_COORDS.put("青岛", new double[]{36.0662, 120.3826});
        CITY_COORDS.put("沈阳", new double[]{41.8057, 123.4315});
        CITY_COORDS.put("大连", new double[]{38.9140, 121.6147});
        CITY_COORDS.put("济南", new double[]{36.6512, 116.9972});
        CITY_COORDS.put("厦门", new double[]{24.4798, 118.0894});
        CITY_COORDS.put("福州", new double[]{26.0743, 119.2965});
        CITY_COORDS.put("昆明", new double[]{25.0389, 102.7183});
        CITY_COORDS.put("哈尔滨", new double[]{45.8038, 126.5350});
        CITY_COORDS.put("南宁", new double[]{22.8170, 108.3665});
        CITY_COORDS.put("合肥", new double[]{31.8206, 117.2272});
        CITY_COORDS.put("London", new double[]{51.5074, -0.1278});
        CITY_COORDS.put("纽约", new double[]{40.7128, -74.0060});
        CITY_COORDS.put("Tokyo", new double[]{35.6762, 139.6503});
    }

    @Tool(description = "计算两个地点之间的直线距离（公里），支持中文城市名（如 北京、上海）或经纬度坐标（如 39.9,116.4）")
    public String calculateDistance(
            @ToolParam(name = "pointA", description = "起点，城市名或'纬度,经度'格式") String pointA,
            @ToolParam(name = "pointB", description = "终点，城市名或'纬度,经度'格式") String pointB) {

        double[] coordA = parseCoord(pointA);
        double[] coordB = parseCoord(pointB);

        if (coordA == null) return "无法识别起点: " + pointA + "，请使用城市名或'纬度,经度'坐标";
        if (coordB == null) return "无法识别终点: " + pointB + "，请使用城市名或'纬度,经度'坐标";

        double distance = haversine(coordA[0], coordA[1], coordB[0], coordB[1]);
        String cityA = findCityName(coordA);
        String cityB = findCityName(coordB);

        return String.format("""
                距离计算结果:
                起点: %s (%.4f, %.4f)
                终点: %s (%.4f, %.4f)
                直线距离: %.2f 公里
                等效英里: %.2f 英里
                """, cityA, coordA[0], coordA[1], cityB, coordB[0], coordB[1], distance, distance * 0.621371);
    }

    @Tool(description = "根据距离和带宽需求推荐网络设备配置方案")
    public String suggestNetworkDevices(
            @ToolParam(name = "distanceKm", description = "两点间距离（公里）") double distanceKm,
            @ToolParam(name = "bandwidthMbps", description = "预期带宽需求（Mbps），默认1000") int bandwidthMbps) {

        StringBuilder sb = new StringBuilder();
        sb.append("网络设备选型方案（距离=").append(String.format("%.1f", distanceKm))
                .append("km, 带宽=").append(bandwidthMbps).append("Mbps）:\n\n");

        sb.append("【传输介质】\n");
        if (distanceKm <= 0.1) {
            sb.append("  推荐: Cat6a 屏蔽网线（10GBASE-T）\n  最大段长: 100m，无需中继\n");
        } else if (distanceKm <= 10) {
            sb.append("  推荐: 单模OS2光纤（1000BASE-LX / 10GBASE-LR）\n");
            if (distanceKm > 5) sb.append("  中继需求: ").append((int) Math.ceil(distanceKm / 5) - 1).append(" 个中继放大器\n");
        } else if (distanceKm <= 40) {
            sb.append("  推荐: 单模OS2光纤 + 10GBASE-ER 光模块\n");
            sb.append("  中继需求: ").append((int) Math.ceil(distanceKm / 20) - 1).append(" 个中继放大器\n");
        } else if (distanceKm <= 80) {
            sb.append("  推荐: 单模OS2光纤 + 40GBASE-ER4 光模块\n");
            sb.append("  中继需求: ").append((int) Math.ceil(distanceKm / 40) - 1).append(" 个中继放大器\n");
        } else {
            sb.append("  推荐: DWDM 密集波分复用系统 + 单模G.652D光纤\n");
            sb.append("  光放大器需求: ").append((int) Math.ceil(distanceKm / 80)).append(" 个（EDFA，约每80km一个）\n");
        }

        sb.append("\n【路由器】\n");
        if (bandwidthMbps <= 100) sb.append("  推荐: 企业级路由器（如 Cisco ISR 1100 / 华为 AR系列）\n");
        else if (bandwidthMbps <= 1000) sb.append("  推荐: 中端边缘路由器（如 Cisco ISR 4300 / 华为 NE20E）\n");
        else if (bandwidthMbps <= 10000) sb.append("  推荐: 高端核心路由器（如 Cisco ASR 9000 / 华为 NE40E）\n");
        else sb.append("  推荐: 运营商级核心路由器（如 Cisco NCS 5500 / 华为 NE5000E）\n");

        int switchCount = Math.max(2, (int) Math.ceil(distanceKm / 50) + 2);
        sb.append("\n【交换机】\n  数量: ").append(switchCount).append(" 台\n");
        if (bandwidthMbps <= 1000) sb.append("  推荐: 千兆L3交换机（如 Cisco Catalyst 9200 / 华为 S5700）\n");
        else if (bandwidthMbps <= 10000) sb.append("  推荐: 万兆L3交换机（如 Cisco Catalyst 9300 / 华为 S7700）\n");
        else sb.append("  推荐: 25G/100G 数据中心交换机（如 Cisco Nexus 9000 / 华为 CE16800）\n");

        sb.append("\n【布线方案】\n  主干线路: ").append(distanceKm >= 40 ? "地埋光缆（需穿管保护）" : "架空/管道光缆").append("\n");
        sb.append("  冗余保护: ").append(distanceKm > 10 ? "建议1+1冗余备份" : "单链路即可").append("\n");

        return sb.toString();
    }

    @Tool(description = "规划子网IP地址，计算可用网段、掩码、可用主机数")
    public String planSubnet(
            @ToolParam(name = "deviceCount", description = "需要接入的设备数量") int deviceCount,
            @ToolParam(name = "baseNetwork", description = "起始网段，如 10.0.0.0/24 或 192.168.1.0") String baseNetwork) {

        if (deviceCount <= 0) return "设备数量必须大于0";

        int requiredHosts = deviceCount + 2;
        int hostBits = 0;
        while ((1L << hostBits) - 2 < requiredHosts && hostBits < 32) hostBits++;
        int maskBits = Math.max(8, Math.min(30, 32 - hostBits));

        String networkStr = baseNetwork.contains("/") ? baseNetwork : baseNetwork + "/24";
        Pattern pattern = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)(?:/(\\d+))?");
        Matcher matcher = pattern.matcher(networkStr);

        if (!matcher.matches()) return "无效的网段格式: " + baseNetwork;

        int baseMask = matcher.group(5) != null ? Integer.parseInt(matcher.group(5)) : 24;
        int maxHosts = (1 << (32 - maskBits)) - 2;

        StringBuilder sb = new StringBuilder();
        sb.append("子网规划方案:\n\n");
        sb.append(String.format("  需求设备数: %d 台\n", deviceCount));
        sb.append(String.format("  推荐掩码: /%d\n", maskBits));
        sb.append(String.format("  可用主机数: %d\n", maxHosts));
        sb.append(String.format("  子网数量: %d（基于 /%d 划分）\n", (int) Math.pow(2, maskBits - baseMask), baseMask));

        sb.append("\n示例子网分配:\n");
        for (int i = 0; i < Math.min(4, Math.pow(2, maskBits - baseMask)); i++) {
            int subnetOffset = i * (1 << (32 - maskBits));
            int network = ipToInt(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
            int subnetNetwork = network + subnetOffset;
            sb.append(String.format("  VLAN %d: %s/%d (可用: %s - %s)\n",
                    i + 10, intToIp(subnetNetwork), maskBits,
                    intToIp(subnetNetwork + 1), intToIp(subnetNetwork + maxHosts)));
        }

        sb.append(maxHosts >= deviceCount ? "\n✅ 子网规划验证通过。" : "\n⚠ 主机数不足，需要扩大子网掩码。");
        return sb.toString();
    }

    @Tool(description = "估算光纤铺设成本，包含材料、施工、设备费用")
    public String estimateFiberCost(
            @ToolParam(name = "distanceKm", description = "铺设距离（公里）") double distanceKm,
            @ToolParam(name = "fiberType", description = "光纤类型：single（单模）或 multi（多模）") String fiberType) {

        boolean isSingleMode = "single".equalsIgnoreCase(fiberType) || "单模".equals(fiberType);
        double fiberUnitPrice = isSingleMode ? 800 : 500;
        double installUnitPrice = distanceKm <= 5 ? 15000 : distanceKm <= 50 ? 12000 : 8000;
        int cores = isSingleMode ? 48 : 24;
        int splicePoints = (int) Math.max(2, Math.ceil(distanceKm / 5) + 2);

        double materialCost = fiberUnitPrice * distanceKm;
        double installCost = installUnitPrice * distanceKm;
        double spliceCost = 80 * cores * splicePoints;
        double odfCost = 800 * Math.ceil(distanceKm / 24);
        double equipmentCost = odfCost + 45 * (int) (distanceKm * 2) + 200 * splicePoints;
        double totalCost = materialCost + installCost + spliceCost + equipmentCost;
        double tax = totalCost * 0.09;
        double grandTotal = totalCost + tax;

        String fiberTypeName = isSingleMode ? "单模G.652D（48芯）" : "多模OM4（24芯）";
        return String.format("""
                光纤铺设成本估算（%s）:
                📦 材料费: %.2f 元
                🔧 施工费: %.2f 元
                💰 总预算: %.2f 元 (约 %.1f 万元)
                """, fiberTypeName, materialCost + equipmentCost, installCost + spliceCost, grandTotal, grandTotal / 10000);
    }

    @Tool(description = "验证网络规划方案的完整性和合理性")
    public String validateNetworkPlan(
            @ToolParam(name = "planJson", description = "网络规划方案的文本描述") String planJson) {

        boolean hasPointA = planJson.contains("pointA") || planJson.contains("起点");
        boolean hasPointB = planJson.contains("pointB") || planJson.contains("终点");
        boolean hasDistance = planJson.contains("distance") || planJson.contains("距离");
        boolean hasDevices = planJson.contains("device") || planJson.contains("设备");

        int passed = 0;
        int total = 4;
        StringBuilder sb = new StringBuilder("网络规划方案验证报告:\n\n");
        sb.append(hasPointA ? "✅" : "❌").append(" 起点: ").append(hasPointA ? "已指定\n" : "缺少\n");
        if (hasPointA) passed++;
        sb.append(hasPointB ? "✅" : "❌").append(" 终点: ").append(hasPointB ? "已指定\n" : "缺少\n");
        if (hasPointB) passed++;
        sb.append(hasDistance ? "✅" : "❌").append(" 距离: ").append(hasDistance ? "已计算\n" : "缺少\n");
        if (hasDistance) passed++;
        sb.append(hasDevices ? "✅" : "❌").append(" 设备: ").append(hasDevices ? "已选型\n" : "缺少\n");
        if (hasDevices) passed++;

        double score = (double) passed / total * 100;
        sb.append("\n完整度: ").append(String.format("%.0f%%", score));
        if (score >= 80) sb.append(" 🎉 方案可行");
        else if (score >= 50) sb.append(" ⚠ 部分缺失");
        else sb.append(" ❌ 需重新规划");
        return sb.toString();
    }

    private double[] parseCoord(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();
        if (CITY_COORDS.containsKey(trimmed)) return CITY_COORDS.get(trimmed);
        for (Map.Entry<String, double[]> e : CITY_COORDS.entrySet()) {
            if (trimmed.contains(e.getKey()) || e.getKey().contains(trimmed)) return e.getValue();
        }
        try {
            String[] parts = trimmed.split("[,\\s，]+");
            if (parts.length >= 2) {
                double lat = Double.parseDouble(parts[0].trim());
                double lon = Double.parseDouble(parts[1].trim());
                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) return new double[]{lat, lon};
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private String findCityName(double[] coord) {
        String nearest = String.format("%.4f,%.4f", coord[0], coord[1]);
        double minDist = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> e : CITY_COORDS.entrySet()) {
            double d = haversine(coord[0], coord[1], e.getValue()[0], e.getValue()[1]);
            if (d < minDist) { minDist = d; nearest = e.getKey(); }
        }
        return minDist > 50 ? String.format("%.4f, %.4f", coord[0], coord[1]) : nearest;
    }

    private int ipToInt(String a, String b, String c, String d) {
        return (Integer.parseInt(a) << 24) | (Integer.parseInt(b) << 16)
                | (Integer.parseInt(c) << 8) | Integer.parseInt(d);
    }

    private String intToIp(int ip) {
        return String.format("%d.%d.%d.%d", (ip >> 24) & 0xFF, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF, ip & 0xFF);
    }
}
