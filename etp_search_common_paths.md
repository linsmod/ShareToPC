
可以排除的（使用时前面加!）
path:regex:[A-Z]:\\Users\\.NET\sv2.0
path:regex:[A-Z]:\\Users\\.NET\sv2.0\sClassic
path:regex:[A-Z]:\\Users\\.NET\sv4.5
path:regex:[A-Z]:\\Users\\.NET\sv4.5\sClassic
path:regex:[A-Z]:\\Users\\Classic\s.NET\sAppPool
path:regex:[A-Z]:\\Users\\DefaultAppPool
path:regex:[A-Z]:\\Users\\Public
path:regex:[A-Z]:\\Users\\([^\\]+)\\AppData
path:regex:[A-Z]:\\Users\\DefaultAppPool
path:regex:[A-Z]:\\Users\\Public\\
path:regex:[A-Z]:\\Users\\Public\sUsers

合并排除（使用时前面加!）
path:regex:[A-Z]:\\Users\\(Public|\.NET|Classic|Default)[^\\]+

桌面下的直接子目录
regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)$

桌面下的递归子目录
regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)

桌面下的文件夹
folder:regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)

桌面下的文件
file:regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)

用户目录|一级
regex:[A-Z]:\\Users\\([^\\]+)\\(Desktop|Documents|Musics|Videos|Downloads)\\([^\\]+)$

用户目录|二级
regex:[A-Z]:\\Users\\([^\\]+)\\(Desktop|Documents|Music|Videos|Downloads)\\([^\\]+(?:\\[^\\]+)?)$



语法：

操作符: 

	space	与 (AND)
	|	或 (OR)
	!	非 (NOT)
	< >	分组
	" "	转义运算字符。

通配符匹配: 

	*	匹配任意字符 0 次或多次(除了 \)。
	**	匹配任意字符 0 次或多次。
	?	匹配任意字符(除了 \)。
	使用通配符时会匹配整个文件名。

字符实体:

	&sp:	空格 ( )
	&vert:	竖线 (|)
	&excl:	感叹号 (!)
	lt:	小于号 (<)
	gt:	大于号 (>)
	quot:	双引号 (")
	#<n>:	十进制 Unicode 字符 <n>。
	#x<n>:	十六进制 Unicode 字符 <n>。

宏: 

	audio:	搜索音频文件。
	zip:	搜索压缩文件。
	doc:	搜索文档文件。
	exe:	搜索可执行文件。
	image:	搜索图片文件。
	video:	搜索视频文件。

修饰符: 

	binary:	按字节流匹配，\\ = \ \xff = hex 字节。
	case:	匹配大小写。
	diacritics:	匹配变音符。
	endwith:	匹配文件名或属性末尾。
	file:	仅匹配文件。
	folder:	仅匹配文件夹。
	hex:	转换双十六进制字符为字节。
	highlight:	高亮搜索词。
	ignorepunc:	忽略文件名或属性标点符。
	ignorews:	忽略文件名或属性中空格符。
	len:	匹配文件名长度或十六进制属性字符。
	fromdisk:	搜索磁盘内容及属性。
	tonumber:	转换文本为数字以对比。
	path:	匹配完整路径。
	prefix:	匹配单词开头。
	regex:	启动正则表达式。
	startwith:	匹配文件名或属性开头。
	suffix:	匹配单词末尾。
	tostring:	转换属性为显示字符以对比。
	whole:	匹配完整文件名或属性。
	ww:	仅匹配完整单词。
	wildcards:	自动内容通配符。
	使用 no 前缀以禁用修饰符。
	修饰符可在搜索功能中组合使用。
	使用 ? 前缀以全局启用修饰符。

通配符: 搜索修饰符语法:

	*	匹配任意字符 0 次或多次。
	?	匹配任意字符。
	#	匹配任意数字 (0-9)。
	[ ]	匹配括号内任一字符。
	[! ]	匹配不在括号内任一字符。
	\	转义后续字符。

函数:

	album:	搜索媒体专辑元数据。
	artist:	搜索媒体艺术家元数据。
	attrib:	搜索指定的文件属性的文件和文件夹。
	bitdepth:	搜索指定像素深度的媒体文件。
	child:	搜索包含匹配文件名文件的文件夹。
	child-count:	搜索包含有指定数目子文件夹或文件的文件夹。
	child-file-count:	搜索包含有指定数目文件的文件夹。
	child-folder-count:	搜索包含有指定数目子文件的文件夹。
	comment:	搜索媒体注释元数据。
	content:	搜索文件内容。
	count:	指定搜索结果最大值。
	dc:	搜索指定创建日期的文件和文件夹。
	depth:	搜索有指定数目父文件夹的文件和文件夹。
	dm:	搜索指定修改日期的文件和文件夹。
	dupe:	搜索指定属性重复的文件和文件夹。
	empty:	搜索空文件夹。
	ext:	搜索指定扩展名的文件 (扩展名以分号分隔)。
	filelist:	搜索指定列表中同名的文件和文件夹 (列表中名称以分号分隔)。
	filelist-filename:	搜索文件名列表中的文件和文件夹。
	frn:	搜索指定文件索引号的文件和文件夹。
	genre:	搜索指定流派的媒体文件。
	height:	搜索指定像素高度的媒体文件。
	index-type:	搜索指定索引类型的文件和文件夹。
	length:	搜索指定时长的媒体文件。
	name:	搜索含有相同名称部分的文件和文件夹。
	orientation:	搜索指定方向的图片 (水平或竖直)。
	parent:	搜索指定路径下的文件和文件夹 (不包含子文件夹)。
	path-part:	搜索含有相同路径部分的文件和文件夹。
	root:	搜索没有父文件夹的文件和文件夹。
	run-count:	搜索指定打开次数的文件和文件夹。
	si:	使用高级查询语法搜索系统索引。
	size:	搜索指定大小的文件 (以字节为单位)。
	star-rating:	搜索指定评分 (1-5) 的媒体文件。
	stem:	搜索含有相同名称部分的文件和文件夹。
	tag:	搜索媒体标签元数据。
	title:	搜索媒体标题元数据。
	track:	搜索媒体音轨元数据。
	type:	搜索指定的文件类型的文件和文件夹。
	width:	搜索指定像素宽度的媒体文件。

函数语法:

	function:value	等于某设定值。
	function:<=value	小于等于某设定值。
	function:<value	小于某设定值。
	function:=value	等于某设定值。
	function:==value	等于某设定值。
	function:>value	大于某设定值。
	function:>=value	大于等于某设定值。
	function:!=value	不等于某设定值。
	function:!value	不等于某设定值。Not equal to value with specified granularity.
	function:start..end	在起始值和终止值的范围内。
	function:start-end	在起始值和终止值的范围内。
	function:<val1 val2>	匹配 val1 且 val2。
	function:<val1 | val2>	匹配 val1 或 val2。
	function:val1;val2	匹配 val1 或 val2。

大小语法:

	size[kb|mb|gb]

大小常数:

	empty	空
	tiny	0 KB < 大小 <= 10 KB
	small	10 KB < 大小 <= 100 KB
	medium	100 KB < 大小 <= 1 MB
	large	1 MB < 大小 <= 16 MB
	huge	16 MB < 大小 <= 128 MB
	gigantic	大小 > 128 MB
	unknown

时长语法:

	[[[DD:]HH:]MM:]SS[.sss]
	[<n><d|day|days>][<n><h|hour|hours>][<n><m|min|mins|minute|minutes>][<n><s|sec|secs|second|seconds>]
	P[n]Y[n]M[n]DT[n]H[n]M[n]S
	P[n]W
	PYYYYMMDDThhmmss
	P[YYYY]-[MM]-[DD]T[hh]:[mm]:[ss]

时长常数:

	very-short	小于 1 分钟。
	short	1 - 5 分钟。
	medium	5 - 30 分钟。
	long	30 - 60 分钟。
	very-long	超过 60 分钟。
	unknown

日期语法:

	year
	month/year 或者 year/month 取决于本地设置
	day/month/year, month/day/year 或者 year/month/day 取决于本地设置
	YYYY[-MM[-DD[Thh[:mm[:ss[.sss]]]]]]
	YYYYMMDD[Thh[mm[ss[.sss]]]]]
	YYMMDD
	FILETIME (>99999999)

日期常数:

	today
	yesterday
	<last|current|this|coming|next><year|month|week>
	<last|coming|next><x><years|months|weeks|days|hours|minutes|mins|seconds|secs>
	past[x]<year[s]|month[s]|week[s]|day[s]|hour[s]|minute[s]|min[s]|second[s]|sec[s]>
	<prev|previous>[x]<year[s]|month[s]|week[s]>
	january|february|march|april|may|june|july|august|september|october|november|december
	jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec
	sunday|monday|tuesday|wednesday|thursday|friday|saturday
	sun|mon|tue|wed|thu|fri|sat
	mtd|ytd
	unknown

属性常数:

	A	存档
	C	压缩
	D	目录
	E	加密
	H	隐藏
	I	未索引的内容
	L	重解析点
	M	访问时召回
	N	一般
	O	脱机
	P	稀疏文件
	R	只读
	S	系统
	T	临时
	U	仅在线
	V	完整流
	X	不可擦洗

文件名组成:

	完整路径	C:\folder\file.txt
	名称	file.txt
	路径	C:\folder
	主文件名	file
	扩展名	txt

示例:

	c:	搜索 C: 盘中的文件和文件夹。
	d:|e:	搜索 D: 或 E: 盘中的文件和文件夹。
	!f:	搜索不在 C: 盘中的文件和文件夹。
	\downloads	搜索 Downloads 文件夹下的文件和文件夹。
	!\appdata	搜索不在 appdata 文件夹下的文件和文件夹。
	c:\windows\	搜索在 C:\Windows 文件夹下的文件和文件夹。
	"c:\Program Files\"	搜索 C:\Program Files 文件夹下的文件和文件夹。
	size:>1gb	搜索大小大于 1 GB 的文件和文件夹。
	size:10mb..50mb	搜索大小在 10 MB 至 50 MB 之间的文件和文件夹。
	size:<1mb	搜索大小小于 1 MB 的文件和文件夹。
	dm:today	搜索修改日期在今天的文件和文件夹。
	dm:>2022	搜索修改日期时在2022年后的文件和文件夹。
	dm:2021-07..2022-06	搜索修改日期在 2021-07 和 2022-06 之间的文件和文件夹。
	dm:<2020	搜索修改日期时在2022年前的文件和文件夹。
	*.mp3	搜索 mp3 文件。
	ext:jpg;png	搜索扩展名匹配 jpg 或 png 的文件和文件夹。
	attrib:h	搜索具备隐藏属性的文件和文件夹。
	length:>1hour	搜索时长大于 1 小时的媒体文件。
	length:10mins..30mins	搜索时长在 10 至 30 分钟的媒体文件。
	length:<1min	搜索时长小于 1 分钟的媒体文件。
	content:abc	搜索内容包含有 abc 的文件。
	content:<abc 123>	搜索内容包含 abc 和 123 的文件。
	regex:content:^foo	搜索内容以 foo 开头的文件。


