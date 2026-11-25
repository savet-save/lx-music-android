/**
 * @name OIAPI 酷我（128 / 320 kbps）
 * @description 仅支持酷我；核对歌曲编号和接口返回码率，所选音质不可用时明确报错。
 * @version 1.0.0
 * @author LX Music Native
 * @homepage https://oiapi.net/index/doc?id=79
 */

// API documentation: https://oiapi.net/index/doc?id=79
// br is a one-based index into each song's types array, not a fixed bitrate code.
// Availability depends on the external API; the user can select another bundled source.
const API = 'https://oiapi.net/api/Kuwo';
const { EVENT_NAMES, request, on, send } = globalThis.lx;
const BITRATES = { '128k': 128, '320k': 320 };

function getJson(params) {
  const query = Object.keys(params).map(key =>
    encodeURIComponent(key) + '=' + encodeURIComponent(params[key])
  ).join('&');
  return new Promise((resolve, reject) => {
    request(API + '?' + query, { method: 'GET', timeout: 8000 }, (error, response) => {
      if (error) return reject(new Error('OIAPI 请求失败：' + error.message));
      if (!response || response.statusCode < 200 || response.statusCode >= 300) {
        return reject(new Error('OIAPI HTTP ' + (response ? response.statusCode : '无响应')));
      }
      try {
        const body = typeof response.body === 'string' ? JSON.parse(response.body) : response.body;
        if (!body || typeof body !== 'object') throw new Error('返回内容不是 JSON 对象');
        resolve(body);
      } catch (_) {
        reject(new Error('OIAPI 返回格式无效'));
      }
    });
  });
}

function songId(value) {
  return String(value == null ? '' : value).replace(/^MUSIC_/i, '').trim();
}

function text(value) {
  return String(value || '').replace(/\s+/g, '').toLowerCase();
}

function singers(value) {
  return String(value || '').split(/[&、,，;；/|]/).map(text).filter(Boolean).sort().join('|');
}

async function musicUrl(source, info) {
  if (source !== 'kw') throw new Error('此音源仅支持酷我，请选择酷我搜索结果');
  const bitrate = BITRATES[info && info.type];
  if (!bitrate) throw new Error('此音源仅支持 128 / 320 kbps');
  const song = info.musicInfo || {};
  if (!song.name) throw new Error('缺少歌曲名称');
  const expectedId = songId(song.songmid || song.rid || song.id);
  const params = { msg: [song.name, song.singer].filter(Boolean).join(' '), limit: 10 };
  const catalog = await getJson(params);
  if (!Array.isArray(catalog.data)) throw new Error('OIAPI 未返回歌曲列表');

  // Prefer the native Kuwo ID. Never substitute a Live/cover/instrumental version.
  const matches = catalog.data.map((item, index) => ({ item, index })).filter(({ item }) => {
    if (!item || typeof item !== 'object') return false;
    if (expectedId) return songId(item.rid || item.id) === expectedId;
    return Boolean(song.singer) && text(item.song) === text(song.name) && singers(item.singer) === singers(song.singer);
  });
  if (matches.length !== 1) throw new Error('未找到唯一匹配的酷我版本，请换一个酷我搜索结果');
  const { item, index } = matches[0];
  const matchedId = songId(item.rid || item.id);
  if (!matchedId) throw new Error('OIAPI 未返回歌曲编号');
  const types = Array.isArray(item.types) ? item.types : [];
  const qualityIndex = types.findIndex(type => type && Number(type.bitrate) === bitrate && text(type.format) === 'mp3');
  if (qualityIndex < 0) throw new Error('这首歌未提供 ' + bitrate + ' kbps MP3');

  const result = await getJson({ ...params, n: index + 1, br: qualityIndex + 1 });
  const audio = result.data;
  if (!audio || typeof audio !== 'object' || Array.isArray(audio)) throw new Error('OIAPI 未返回音频信息');
  if (songId(audio.rid || audio.id) !== matchedId) throw new Error('返回的歌曲版本发生变化，请重试');
  if (Number(audio.bitrate) !== bitrate || text(audio.format) !== 'mp3') {
    throw new Error('音源未返回所选的 ' + bitrate + ' kbps MP3，已拒绝低码率替代');
  }
  if (typeof audio.url !== 'string' || !/^https?:\/\/[^\s]+$/i.test(audio.url)) {
    throw new Error('OIAPI 未返回有效的音频链接');
  }
  return audio.url;
}

on(EVENT_NAMES.request, ({ source, action, info }) => {
  if (action !== 'musicUrl') return Promise.reject(new Error('不支持的请求：' + action));
  return musicUrl(source, info);
});

send(EVENT_NAMES.inited, {
  sources: {
    kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] },
  },
});
