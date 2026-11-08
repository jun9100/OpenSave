// Simplified Chinese for errors from the background service (lib/i18n.js
// trError): recognised by what they are about, since their wording comes
// from all over the Go side. The original stays alongside in brackets.
export default [
  // Pairing errors first: the generic "not authenticated", "401" and
  // "invalid" rules below would otherwise claim these messages and lose the
  // one detail that tells the user what to do next.
  [/pairing code must be 8 letters or digits/i, '配对码格式不对，应为 8 位字母或数字，如 ABCD-EFGH'],
  [/not a SaveVault pairing link/i, '这不是有效的配对链接'],
  [/confirm the server's host name before pairing/i, '请先确认服务器主机名'],
  [/the service does not support pairing/i, '服务端不支持配对'],
  [/the pairing code is invalid, expired, or already used/i, '配对码无效、已过期或已被使用'],
  [/too many failed pairing attempts; try again later/i, '配对失败次数过多，请稍后再试'],
  [/game-save revoked this device's token; pair this device again/i, '此设备已被移除，请重新配对'],
  [/game-save no longer accepts the shared sync key; pair this device/i, '服务端已停用共享令牌，请配对本设备'],
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
  [/(inside|contains) the tracked folder of/i, '这个文件夹和一个已跟踪的游戏重叠（在它里面或包含它）；同时跟踪两份会让每次存档都备份两次，请只保留一份'],
  [/already track|already exists/i, '已经存在'],
  [/cloud version failed verification|archive does not match its recorded checksum/i, '这个云端版本校验失败'],
  [/could not take a safety snapshot before restoring|safety snapshot before restoring/i, '安全快照失败，未做任何改动'],
  [/choose a restore target for at least one location/i, '请为至少一个位置选择恢复目标'],
  [/\bsave is in use\b/i, '存档正在使用中'],
  [/single-file save locations? cannot be restored/i, '单文件存档位置暂不支持跨设备恢复'],
  [/cannot restore from this device's own cloud scope/i, '不能从本机自己的云端范围恢复'],
  [/not grouped with this game in the cloud|cloud service does not group games/i, '云端没有把这份存档和本游戏归为同一个游戏，不能恢复'],
  [/cloud save (manifest|archive)|foreign save archive is invalid/i, '云端存档清单无效'],
  [/not found/i, '找不到对象'],
  [/required|invalid/i, '请求参数无效']
];
