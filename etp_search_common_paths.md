
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

