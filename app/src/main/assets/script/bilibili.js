/**
 * @name B站音源
 * @description 使用原生接口播放视频原始音频，下载时转码为 MP3；可在设置中填写 SESSDATA。
 * @version 1.0.0
 * @author LX Music Native
 */
// Requests are implemented by the native Bilibili adapter, without exposing account cookies to scripts.
lx.on(lx.EVENT_NAMES.request, () => { throw new Error('请使用原生 B站音源适配器'); });
lx.send(lx.EVENT_NAMES.inited, {sources:{bili:{type:'music',actions:['musicUrl'],qualitys:['128k']}}});
