桌面遍历：
regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)

桌面直属 regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)$
文件夹 folder:regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)$
文件 file:regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+)$

搜索用户目录一级内容
regex:[A-Z]:\\Users\\([^\\]+)\\(Desktop|Documents|Musics|Videos|Downloads)\\([^\\]+)$

regex:[A-Z]:\\Users\\([^\\]+)\\(Desktop|Documents|Musics|Videos|Downloads)\\([^\\]+)$ file:

regex:[A-Z]:\\Users\\([^\\]+)\\(Desktop|Documents|Musics|Videos|Downloads)\\([^\\]+)$ folder:

搜索用户目录,深度2
regex:[A-Z]:\\Users\\([^\\]+)\\(Desktop|Documents|Music|Videos|Downloads)\\([^\\]+(?:\\[^\\]+)?)$

排除
regex:[A-Z]:\\Users\\([^\\]+)\\(Desktop|Documents|Music|Videos|Downloads)\\([^\\]+(?:\\[^\\]+)?)$ !path:regex:[A-Z]:\\Users\\Public\\


无结果
(regex:[A-Z]:\\Users\\([^\\]+)\\Desktop\\([^\\]+) | regex:[A-Z]:\\Users\\([^\\]+)\\Documents\\([^\\]+) | regex:[A-Z]:\\Users\\([^\\]+)\\Downloads\\([^\\]+) | regex:[A-Z]:\\Users\\([^\\]+)\\Videos\\([^\\]+) | regex:[A-Z]:\\Users\\([^\\]+)\\Pictures\\([^\\]+) | regex:[A-Z]:\\Users\\([^\\]+)\\Musics\\([^\\]+)) 手机

