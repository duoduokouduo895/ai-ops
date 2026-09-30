import { collectOverview } from '../lib/overview'

const RESPONSE_HEADERS = {
  'Cache-Control': 'private, no-store, max-age=0',
  'Content-Type': 'application/json; charset=utf-8',
  'X-Content-Type-Options': 'nosniff',
}

export const onRequestGet: PagesFunction<Env> = async ({ env }) => {
  try {
    const overview = await collectOverview(env)
    return new Response(JSON.stringify(overview), { status: 200, headers: RESPONSE_HEADERS })
  } catch (error) {
    console.error(JSON.stringify({ message: 'homeops_overview_unhandled', error: error instanceof Error ? error.message : 'unknown_error' }))
    return new Response(JSON.stringify({ error: '暂时无法生成家庭服务器概览。' }), { status: 500, headers: RESPONSE_HEADERS })
  }
}
