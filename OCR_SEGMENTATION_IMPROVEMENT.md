# OCR 候选词分割算法优化详解

## 问题回顾

### 失败案例分析

| 案例 | 拼音 | 目标词 | OCR原始输出 | 期望分割 | 实际分割 | 问题 |
|------|------|--------|-----------|--------|--------|------|
| 1 | xfz | 小饭桌 | `消費者 修房子 小饭桌新发見` | 4个词 | 3个词 | "小饭桌"与"新发見"之间无空白 |
| 2 | fx | 发泄 | `放学 发型风险 放心分享` | 5个词 | 3个词 | "发型风险"应拆为2个，"放心分享"也应拆为2个 |
| 3 | tll | 太累了 | `甜啦啦 田林路太累了 大懒了` | 4个词 | 3个词 | "田林路太累了"应拆为2个词 |
| 4 | ql | 去了 | `抢了 青楼 去啦起来去了` | 5个词 | 3个词 | 最后无空白段应拆为2个词 |

## 根本原因

**OCR原始文本缺少空白符的原因：**

1. **候选词区域是连续图像行** - 从左到右排列
2. **词间空白是视觉间隙** - 空像素区域，不是实际字符
3. **MLKit按字符密集度识别** - 只有当间隙明显大于字符宽度时才插入空白
4. **实际场景** - 很多词间隙不够大，OCR直接连接

### 具体例子

```
视觉上看（候选词栏）：
┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐
 放学    发型    风险    放心    分享
└──────┘ └──────┘ └──────┘ └──────┘ └──────┘
   ▲        ▲        ▲        ▲
 间隙1    间隙2    间隙3    间隙4
(~12px)  (~12px)  (~12px)  (~12px)

MLKit识别：
"放学 发型风险 放心分享"

原因：间隙2和3不够大，被合并
```

## 解决方案：纯图像列分割

不再依赖OCR输出的空白符，而是**直接分析图像本身**。

### 算法步骤

#### Step 1: 计算每列的暗像素密度

```
对图像的每一列（从左到右）：
  
  列x的暗度 = 该列中暗像素的数量
  
  示意（宽度=50px，高度=30px）：
  
  列: 0  1  2  3  4  5  6  7  8  9  10 ... (中间省略) ... 47 48 49
  
  墨迹: ████████░░░░░░░░████████░░░░░░░████████░░░░░░████████
        ↓      ↓      ↓      ↓      ↓      ↓
      字符块1  空白   字符块2  空白   字符块3  空白
      (0-7)   (8-15) (16-23) (24-31) (32-39) (40-49)
```

#### Step 2: 识别"墨迹块"（连续的暗列）

```
设定阈值 = 图像高度 × 5%（即至少要有5%的列高要有墨迹）

识别规则：
  - 如果列的暗度 >= 阈值 → 属于"墨迹"
  - 连续的墨迹列 → 构成一个"墨迹块"
  - 间隔的墨迹 → 分开成多个块

结果：
  墨迹块1: 列0-7   (宽度8)
  墨迹块2: 列16-23 (宽度8)
  墨迹块3: 列32-39 (宽度8)
```

#### Step 3: 计算块间间隙

```
相邻块的间隙 = 下一个块的起点 - 当前块的终点 - 1

例：
  块1终点=7, 块2起点=16 → 间隙 = 16 - 7 - 1 = 8px
  块2终点=23, 块3起点=32 → 间隙 = 32 - 23 - 1 = 8px

间隙列表: [8, 8, ...] (待确认)
```

#### Step 4: 自适应判断词界阈值（核心创新）

**问题：** 什么间隙才是"词界"？

**答案：** 用"双峰聚类"方法。

**思想：**
- 同一个词的不同笔画间隙 → 小（通常 1-3 px）
- 不同词之间的间隙 → 大（通常 8-20 px）
- 这形成"双峰"分布

**计算方法：**

```
1. 对所有间隙排序
   例: [1, 1, 1, 2, 1] → sorted: [1, 1, 1, 1, 2]

2. 寻找最大"跳跃点"
   位置0→1: 1→1, jump=0
   位置1→2: 1→1, jump=0
   位置2→3: 1→1, jump=0
   位置3→4: 1→2, jump=1 ← 最大跳跃
   
3. 判断是否有明显双峰
   if 最大跳跃 >= 4px:
     threshold = (sorted[3] + sorted[4]) / 2
               = (1 + 2) / 2 = 1.5 px
   else:
     threshold = sorted.last() + 1 = 2 + 1 = 3 px

4. 应用阈值
   if 间隙 >= threshold → 这是词界，拆开
   else → 这是笔画间隙，合并
```

**复杂例子（fx案例）：**

```
原始块间隙: [12, ?, 12]
(具体值待实际检测)

如果: [1, 1, 12, 12]
  sorted: [1, 1, 12, 12]
  最大跳: 1→12, jump=11 >= 4 ← 明显双峰！
  threshold = (1 + 12) / 2 = 6.5 px
  
应用：
  - 间隙=1 < 6.5 → 合并 (同词内笔画)
  - 间隙=12 >= 6.5 → 拆开 (词界)
```

#### Step 5: 按阈值分割

```
从左到右遍历块，根据阈值决定拆还是合：

初始: curWord = 块1
      nextBlock = 块2

条件检查:
  if (块2起点 - 块1终点) >= threshold:
    ← 词界！
    保存 curWord = 块1
    curWord = 块2
  else:
    ← 笔画间隙！
    合并 curWord = 块1..块2

最终词: [块1], [块2], [块3], ...
```

#### Step 6: 逐块单独OCR

```
对每个分割块：
  1. 裁剪图像 = bitmap.crop(块的矩形范围)
  2. 单独OCR识别 = runOcrRawText(裁剪后的图像)
  3. 规范化 = normalizeOcrToken(识别结果)
  4. 保存结果
  
好处：
  - 识别准确度更高（聚焦小区域）
  - 避免OCR整行识别的交叉干扰
  - 如果某块识别失败，其他块不受影响
```

## 代码改动

### 主要改动点

#### 1. 删除空白分割
```kotlin
// 旧代码：依赖空白符
val tokensByWhitespace = rawText.split(Regex("\\s+"))  // ❌ 删除

// 新代码：纯图像分割
val segmentRects = splitByVerticalWhitespaceAdaptive(bitmap)
```

#### 2. 重写 `splitByVerticalWhitespaceAdaptive()`
- 计算列暗度
- 识别墨迹块
- 计算相邻块间隙
- 调用自适应阈值计算
- 按阈值合并/拆分块
- 逐块单独OCR

#### 3. 新增 `calcAdaptiveSplitThreshold()`
- 实现双峰聚类
- 自动寻找最大跳跃点
- 计算词界阈值

### 关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `darkThreshold` | 235 | RGB值 > 235为"亮像素"，< 235为"暗像素" |
| `inkThreshold` | height × 5% | 列要至少有5%的行数是墨迹 |
| `minRunWidth` | width × 1.5% | 块的最小宽度，过小的块忽略 |
| `padX` | 4px | 块的左右边距 |
| `padY` | 2px | 块的上下边距 |
| 跳跃判定 | >= 4px | 最大跳跃 >= 4px才认为是双峰 |

## 日志输出

新增详细的调试日志，格式如下：

```
D/KeyboardEvaluator: OCR rawText: '消費者 修房子 小饭桌新发見'
D/KeyboardEvaluator: Column darkness distribution: width=200, height=50
D/KeyboardEvaluator: Ink threshold: 3 (height * 5%)
D/KeyboardEvaluator: Ink runs (continuous dark columns): 4
D/KeyboardEvaluator:   run[0]: cols 10-25 (16 cols wide)
D/KeyboardEvaluator:   run[1]: cols 45-60 (16 cols wide)
D/KeyboardEvaluator:   run[2]: cols 80-95 (16 cols wide)
D/KeyboardEvaluator:   run[3]: cols 110-130 (21 cols wide)
D/KeyboardEvaluator: Gaps between ink runs: [19, 19, 14] (3 gaps)
D/KeyboardEvaluator: Sorted gaps: [14, 19, 19]
D/KeyboardEvaluator:   Jump at idx 0: 14 → 19 (jump=5)
D/KeyboardEvaluator:   Jump at idx 1: 19 → 19 (jump=0)
D/KeyboardEvaluator: Best jump: 5 at idx 0
D/KeyboardEvaluator: Dual-peak detected: threshold = (14 + 19) / 2 = 16
D/KeyboardEvaluator: Adaptive split threshold: 16
D/KeyboardEvaluator:   Split at gap=19 >= 16
D/KeyboardEvaluator:   Split at gap=19 >= 16
D/KeyboardEvaluator:   Merge at gap=14 < 16, now curRun=80-130
D/KeyboardEvaluator: Final word runs: 3
D/KeyboardEvaluator:   word[0]: cols 10-25 (16 cols wide)
D/KeyboardEvaluator:   word[1]: cols 45-60 (16 cols wide)
D/KeyboardEvaluator:   word[2]: cols 80-130 (51 cols wide)
D/KeyboardEvaluator: Image segments: 3
D/KeyboardEvaluator:   segment[0] -> '消費者' rect=Rect(6, -2, 29, 52)
D/KeyboardEvaluator:   segment[1] -> '修房子' rect=Rect(41, -2, 64, 52)
D/KeyboardEvaluator:   segment[2] -> '小饭桌新发見' rect=Rect(76, -2, 134, 52)
D/KeyboardEvaluator: Final tokens: 3
D/KeyboardEvaluator:   [0] '消費者'
D/KeyboardEvaluator:   [1] '修房子'
D/KeyboardEvaluator:   [2] '小饭桌新发見'
```

## 预期改进结果

### 理论分析

对于 `fx` 案例（放学、发型、风险、放心、分享）：

```
1. 计算列暗度
   → 5个字符块，间隙4个

2. 识别墨迹块
   → 5个块（各对应一个字）

3. 计算间隙
   → gaps = [8, 8, 8, 8] (像素)
   
   *假设实际间隙都是8px

4. 自适应阈值
   sorted = [8, 8, 8, 8]
   最大跳跃 = 0 (无明显双峰)
   threshold = 8 + 1 = 9px
   
5. 分割
   if gap=8 < 9 → 合并？❌ 这会导致所有词合并在一起！
   
   问题：如果所有间隙都相等，无法区分
   
   **需要调整策略**
```

### 可能的边界情况

1. **所有间隙都相等** - 可能是字体大小设置问题，需要调整 `inkThreshold`
2. **某些字符残缺** - 可能导致块识别不准，需要提高 `darkThreshold` 容错
3. **背景不是纯白** - 可能影响暗度计算，需要调整 `darkThreshold`

## 下一步优化方向

如果新算法仍然不能达到预期效果，可以考虑：

1. **动态阈值调整**
   - 根据图像宽度自动调整 `inkThreshold`
   - 根据字体大小推测字符宽度

2. **多通道检测**
   - 同时检测水平和垂直投影
   - 用垂直投影的谷值（valley）作为词界

3. **机器学习模型**
   - 训练模型识别词界特征
   - 用概率判断是否拆分

4. **结合OCR块信息**
   - 虽然OCR的块级别信息不全，但可以作为参考
   - 优先拆分OCR预判有问题的块

## 测试方法

1. 在真机上运行测试，查看 logcat 日志
2. 对比实际分割结果与期望结果
3. 根据日志调整参数
4. 保存失败的截图和调试信息，用于后续分析


