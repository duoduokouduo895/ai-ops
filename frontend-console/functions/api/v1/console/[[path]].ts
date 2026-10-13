import { proxyConsole } from '../../../lib/consoleProxy'

export const onRequest: PagesFunction<Env> = (context) =>
  proxyConsole(context.request, context.env.AIDIAG_API_ORIGIN)
