// Simplified Chinese for errors from the background service (lib/i18n.js
// trError): recognised by what they are about, since their wording comes
// from all over the Go side. The original stays alongside in brackets.
export default [
  [/no space left|not enough space|disk full|enospc|quota/i, '磁盘或云端空间不足'],
  [/permission denied|access is denied|eacces|eperm|read-only file system/i, '没有权限（文件只读或被其他程序占用）'],
  [/being used by another process|sharing violation/i, '文件被其他程序占用'],
  [/\b401\b|unauthori[sz]ed|invalid token|bad token/i, '认证失败（用户名、密码或令牌不正确）'],
  [/\b403\b|forbidden/i, '服务器拒绝访问'],
  [/\b404\b/i, '服务器上不存在该地址'],
  [/\b413\b|too large|exceeds configured size/i, '文件超过服务器允许的大小'],
  [/\b5\d\d\b|bad gateway|service unavailable/i, '服务器出错'],
  [/connection refused|no such host|dial tcp|i\/o timeout|timeout|deadline exceeded|connection reset|network is unreachable|\beof\b/i, '网络连接失败'],
  [/x509|certificate|tls/i, 'HTTPS 证书校验失败'],
  [/conflict copy|needs review before publishing|scope needs review/i, '云端存档有冲突，需要先在游戏的“云端”标签页确认'],
  [/cloud sync not authenticated/i, '云服务尚未登录'],
  [/no (\w+ )?destination( url)? configured/i, '尚未配置云端地址'],
  [/not enabled/i, '云备份未开启'],
  [/snapshot is damaged|checksum|zip: not a valid/i, '快照已损坏'],
  [/no folder to snapshot|no such file or directory|cannot find the (path|file)|does not exist|no longer exists/i, '文件或文件夹不存在'],
  [/already track|already exists/i, '已经存在'],
  [/not found/i, '找不到对象'],
  [/required|invalid/i, '请求参数无效']
];
