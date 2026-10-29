import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { pageNavigationForPath } from '@/navigation/catalog'

/** The URL owns section selection; refresh, Back and contextual links agree. */
export function usePageSection() {
  const route = useRoute()
  const router = useRouter()
  const ownerPath = route.path
  return computed<string>({
    get: () => pageNavigationForPath(ownerPath, route.query).active || 'overview',
    set: value => {
      const navigation = pageNavigationForPath(ownerPath, route.query)
      if (route.path !== ownerPath || !navigation.items.some(item => item.value === value)) return
      if (navigation.active === value) return
      void router.push({ path: ownerPath, query: { ...route.query, [navigation.parameter]: value } })
    },
  })
}
