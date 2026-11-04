import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Shop } from '../types/shop'
import { shopsApi } from '../api/shops'

export const useShopStore = defineStore('shop', () => {
  const shops = ref<Shop[]>([])
  const currentShopId = ref<number | null>(null)

  const currentShop = computed(() => {
    return shops.value.find(s => s.id === currentShopId.value) || null
  })

  const fetchShops = async () => {
    shops.value = await shopsApi.getShops()
    if (shops.value.length > 0 && !currentShopId.value) {
      currentShopId.value = shops.value[0].id
    }
  }

  const setCurrentShop = (shopId: number) => {
    currentShopId.value = shopId
  }

  return {
    shops,
    currentShopId,
    currentShop,
    fetchShops,
    setCurrentShop
  }
})
